//! Bounded, persistent pairing authorisation. The host owns encrypted storage.
use crate::{Error, Result};
use ed25519_dalek::{Signature, VerifyingKey};
use rand::{rngs::OsRng, Rng};
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

#[derive(Clone, PartialEq, Eq)]
pub struct PairedClient {
    pub identifier: String,
    pub public_key: [u8; 32],
}
pub type PersistPairings =
    Arc<dyn Fn(&[PairedClient]) -> std::result::Result<(), String> + Send + Sync>;
struct State {
    clients: BTreeMap<String, [u8; 32]>,
    window: Option<(Instant, u32)>,
    attempts: Vec<Instant>,
}
pub struct PairingStore {
    state: Mutex<State>,
    persist: PersistPairings,
}
impl std::fmt::Debug for PairingStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PairingStore").finish_non_exhaustive()
    }
}
impl PairingStore {
    pub fn new(initial: Vec<PairedClient>, persist: PersistPairings) -> Result<Self> {
        if initial.len() > 16 {
            return Err(Error::Protocol("too many paired clients".into()));
        }
        let mut clients = BTreeMap::new();
        for p in initial {
            validate_identifier(&p.identifier)?;
            VerifyingKey::from_bytes(&p.public_key)
                .map_err(|_| Error::Crypto("invalid client key".into()))?;
            if clients.insert(p.identifier, p.public_key).is_some() {
                return Err(Error::Protocol("duplicate paired identifier".into()));
            }
        }
        Ok(Self {
            state: Mutex::new(State {
                clients,
                window: None,
                attempts: vec![],
            }),
            persist,
        })
    }
    pub fn open_pairing(&self, duration: Duration) -> u32 {
        let pin = OsRng.gen_range(1000..=9999);
        let mut state = self.state.lock().unwrap();
        state.window = Some((Instant::now() + duration.min(Duration::from_secs(120)), pin));
        pin
    }
    pub fn close_pairing(&self) {
        self.state.lock().unwrap().window = None;
    }
    pub fn list(&self) -> Vec<PairedClient> {
        self.state
            .lock()
            .unwrap()
            .clients
            .iter()
            .map(|(id, key)| PairedClient {
                identifier: id.clone(),
                public_key: *key,
            })
            .collect()
    }
    pub fn revoke(&self, id: &str) -> Result<()> {
        let mut state = self.state.lock().unwrap();
        let mut next = state.clients.clone();
        next.remove(id);
        self.persist_map(&next)?;
        state.clients = next;
        Ok(())
    }
    pub(crate) fn key(&self, id: &str) -> Option<[u8; 32]> {
        self.state.lock().unwrap().clients.get(id).copied()
    }
    pub(crate) fn begin(&self) -> Result<u32> {
        let mut state = self.state.lock().unwrap();
        let now = Instant::now();
        state
            .attempts
            .retain(|at| now.duration_since(*at) < Duration::from_secs(60));
        let pin = state
            .window
            .filter(|(end, _)| *end > now)
            .map(|(_, pin)| pin)
            .ok_or_else(|| Error::Protocol("pairing window closed".into()))?;
        if state.attempts.len() >= 5 {
            return Err(Error::Protocol("pairing attempt limit".into()));
        }
        state.attempts.push(now);
        Ok(pin)
    }
    pub(crate) fn is_open(&self, pin: u32) -> bool {
        self.state
            .lock()
            .unwrap()
            .window
            .is_some_and(|(end, current)| end > Instant::now() && current == pin)
    }
    pub(crate) fn authorise(&self, client: PairedClient, pin: u32) -> Result<()> {
        validate_identifier(&client.identifier)?;
        let mut state = self.state.lock().unwrap();
        if !state
            .window
            .is_some_and(|(end, current)| end > Instant::now() && current == pin)
        {
            return Err(Error::Protocol("pairing window expired".into()));
        }
        if let Some(existing) = state.clients.get(&client.identifier) {
            if existing != &client.public_key {
                return Err(Error::Protocol("client identifier already paired".into()));
            }
        }
        let mut next = state.clients.clone();
        next.insert(client.identifier, client.public_key);
        if next.len() > 16 {
            return Err(Error::Protocol("paired device limit".into()));
        }
        self.persist_map(&next)?;
        state.clients = next;
        state.window = None;
        Ok(())
    }
    fn persist_map(&self, clients: &BTreeMap<String, [u8; 32]>) -> Result<()> {
        let list = clients
            .iter()
            .map(|(id, key)| PairedClient {
                identifier: id.clone(),
                public_key: *key,
            })
            .collect::<Vec<_>>();
        (self.persist)(&list).map_err(|_| Error::Protocol("pairing storage unavailable".into()))
    }
}
pub(crate) fn validate_identifier(id: &str) -> Result<()> {
    if id.is_empty() || id.len() > 128 || id.chars().any(char::is_control) {
        return Err(Error::Protocol("invalid client identifier".into()));
    }
    Ok(())
}
pub(crate) fn verify_signature(key: &[u8; 32], message: &[u8], signature: &[u8]) -> Result<()> {
    let key =
        VerifyingKey::from_bytes(key).map_err(|_| Error::Crypto("invalid signing key".into()))?;
    let signature = Signature::from_slice(signature)
        .map_err(|_| Error::Crypto("invalid signature length".into()))?;
    key.verify_strict(message, &signature)
        .map_err(|_| Error::Crypto("client signature rejected".into()))
}
#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    fn store() -> PairingStore {
        PairingStore::new(vec![], Arc::new(|_| Ok(()))).unwrap()
    }
    #[test]
    fn closed_expired_and_rate_limited() {
        let s = store();
        assert!(s.begin().is_err());
        s.open_pairing(Duration::ZERO);
        assert!(s.begin().is_err());
        s.open_pairing(Duration::from_secs(120));
        for _ in 0..5 {
            assert!(s.begin().is_ok());
        }
        assert!(s.begin().is_err());
    }
    #[test]
    fn persist_failure_never_grants_access() {
        let s = PairingStore::new(vec![], Arc::new(|_| Err("disk".into()))).unwrap();
        let pin = s.open_pairing(Duration::from_secs(10));
        assert!(s
            .authorise(
                PairedClient {
                    identifier: "phone".into(),
                    public_key: SigningKey::from_bytes(&[1; 32]).verifying_key().to_bytes()
                },
                pin
            )
            .is_err());
        assert!(s.list().is_empty());
    }
    #[test]
    fn successful_pairing_closes_window_and_revocation_removes_key() {
        let s = store();
        let pin = s.open_pairing(Duration::from_secs(10));
        let key = SigningKey::from_bytes(&[1; 32]).verifying_key().to_bytes();
        s.authorise(
            PairedClient {
                identifier: "phone".into(),
                public_key: key,
            },
            pin,
        )
        .unwrap();
        assert_eq!(s.key("phone"), Some(key));
        assert!(s.begin().is_err());
        s.revoke("phone").unwrap();
        assert!(s.key("phone").is_none());
    }
    #[test]
    fn signature_verification_is_real() {
        let key = SigningKey::from_bytes(&[2; 32]);
        let sig = key.sign(b"transcript");
        assert!(verify_signature(
            &key.verifying_key().to_bytes(),
            b"transcript",
            &sig.to_bytes()
        )
        .is_ok());
        assert!(
            verify_signature(&key.verifying_key().to_bytes(), b"changed", &sig.to_bytes()).is_err()
        );
        assert!(verify_signature(
            &SigningKey::from_bytes(&[3; 32]).verifying_key().to_bytes(),
            b"transcript",
            &sig.to_bytes()
        )
        .is_err());
    }
}
