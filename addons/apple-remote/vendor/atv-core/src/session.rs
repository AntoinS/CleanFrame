//! Companion Link session: pairing, verification and the encrypted control
//! channel. Ported from `fake_atv.py`'s `CompanionSession`.

use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::Arc;

use crate::keyboard::{self, KeyboardState};
use crate::pairing::{validate_identifier, verify_signature, PairedClient, PairingStore};
use ed25519_dalek::{Signer, SigningKey};
use rand::{rngs::OsRng, RngCore};
use tokio::io::AsyncWriteExt;
use tokio::net::tcp::OwnedWriteHalf;
use tokio::sync::Mutex;
use tracing::{debug, info};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

use crate::crypto::{hkdf_expand, CompanionCipher};
use crate::delegate::{AtvDelegate, EventKind, TouchPhase, TrackpadMode};
use crate::error::{Error, Result};
use crate::identity::DeviceIdentity;
use crate::inspector::InspectorHub;
use crate::opack::{self, Value};
use crate::registry::{ActiveConnectionTracker, DeviceRegistry};
use crate::srp::SrpServer;
use crate::tlv::{self, tags};
use crate::FrameType;

pub const SERVER_IDENTIFIER: &str = "5D797FD3-3538-427E-A47B-A32FC6CF3A6A";
pub const DEVICE_MODEL: &str = "AppleTV14,1";
pub const MEDIA_CONTROL_VOLUME: u64 = 0x0100;

const SWIPE_MIN_DISTANCE: f64 = 150.0; // touchpad units; the surface is 1000x1000
const TAP_MAX_DISTANCE: f64 = 60.0; // below this a touch sequence counts as a tap

pub type SharedWriter = Arc<Mutex<OwnedWriteHalf>>;

fn hid_command_name(command: u64) -> String {
    match command {
        1 => "up".into(),
        2 => "down".into(),
        3 => "left".into(),
        4 => "right".into(),
        5 => "menu".into(),
        6 => "select".into(),
        7 => "home".into(),
        8 => "volume_up".into(),
        9 => "volume_down".into(),
        10 => "siri".into(),
        11 => "screensaver".into(),
        12 => "sleep".into(),
        13 => "wake".into(),
        14 => "play_pause".into(),
        15 => "channel_up".into(),
        16 => "channel_down".into(),
        17 => "guide".into(),
        // iOS 26/27 repurposes the old PageUp/PageDown wire codes for the
        // Control Center remote's Mute and Power buttons.
        18 => "mute".into(),
        19 => "power".into(),
        other => format!("unknown:{other}"),
    }
}

/// One Companion connection's state machine.
pub struct CompanionSession {
    writer: SharedWriter,
    delegate: Arc<dyn AtvDelegate>,
    device_name: String,
    device_mac: [u8; 6],
    pin: u32,
    pairings: Arc<PairingStore>,
    setup_step: u8,
    pending_verify: Option<([u8; 32], [u8; 32])>,
    verified_client: Option<PairedClient>,
    keyboard_subscribed: bool,
    keyboard_last: Option<KeyboardState>,
    // None: no rejection refresh yet; Some(None): already refreshed no field.
    keyboard_resync_state: Option<Option<KeyboardState>>,
    keyboard_shapes: keyboard::KeyboardShapeLog,
    rate_start: std::time::Instant,
    rate_count: u32,
    _mouse_mode: Arc<AtomicBool>,
    trackpad_mode: Arc<AtomicU8>,
    unique_id: Vec<u8>,
    private_key: [u8; 32],
    signing_key: SigningKey,
    auth_pub: [u8; 32],
    verify_private: StaticSecret,
    verify_pub: X25519PublicKey,
    cipher: Option<CompanionCipher>,
    current_identifier: Option<String>,
    srp: SrpServer,
    remote_session_id: u32,
    session_established: Arc<AtomicBool>,
    touch_origin: Option<(f64, f64)>,
    touch_last: Option<(f64, f64)>,
    touch_moved: bool,
    volume: f64,
    muted: bool,
    system_status: u64,
    display_power_on: bool,
    inspector: Arc<InspectorHub>,
    registry: Arc<Mutex<DeviceRegistry>>,
    active_tracker: Arc<Mutex<ActiveConnectionTracker>>,
    conn_id: u64,
    peer_ip: String,
    device_id: Option<String>,
    timeout_deadline: Arc<Mutex<Option<tokio::time::Instant>>>,
}

impl CompanionSession {
    pub fn new(
        writer: SharedWriter,
        delegate: Arc<dyn AtvDelegate>,
        identity: &DeviceIdentity,
        pairings: Arc<PairingStore>,
        mouse_mode: Arc<AtomicBool>,
        trackpad_mode: Arc<AtomicU8>,
        inspector: Arc<InspectorHub>,
        registry: Arc<Mutex<DeviceRegistry>>,
        active_tracker: Arc<Mutex<ActiveConnectionTracker>>,
        conn_id: u64,
        peer_ip: String,
        session_established: Arc<AtomicBool>,
    ) -> Self {
        let signing_key = SigningKey::from_bytes(&identity.private_key);
        let auth_pub = signing_key.verifying_key().to_bytes();
        let verify_private = StaticSecret::random_from_rng(OsRng);
        let verify_pub = X25519PublicKey::from(&verify_private);
        let remote_session_id: u32 = rand::random();
        let (initial_volume, initial_muted) = delegate.get_audio_state().unwrap_or((0.5, false));
        Self {
            writer,
            delegate,
            device_name: identity.name.clone(),
            device_mac: identity.mac_bytes,
            pin: 0,
            pairings,
            setup_step: 0,
            pending_verify: None,
            verified_client: None,
            keyboard_subscribed: false,
            keyboard_last: None,
            keyboard_resync_state: None,
            keyboard_shapes: keyboard::KeyboardShapeLog::default(),
            rate_start: std::time::Instant::now(),
            rate_count: 0,
            _mouse_mode: mouse_mode,
            trackpad_mode,
            unique_id: identity.server_identifier.as_bytes().to_vec(),
            private_key: identity.private_key,
            signing_key,
            auth_pub,
            verify_private,
            verify_pub,
            cipher: None,
            current_identifier: None,
            srp: SrpServer::new(0, &rand::random::<[u8; 32]>()),
            remote_session_id,
            session_established,
            touch_origin: None,
            touch_last: None,
            touch_moved: false,
            volume: initial_volume,
            muted: initial_muted,
            system_status: 3, // Awake
            display_power_on: true,
            inspector,
            registry,
            active_tracker,
            conn_id,
            peer_ip,
            device_id: None,
            timeout_deadline: Arc::new(Mutex::new(Some(
                tokio::time::Instant::now() + std::time::Duration::from_secs(30),
            ))),
        }
    }

    fn check_authorised(&self) -> Result<()> {
        let client = self
            .verified_client
            .as_ref()
            .ok_or_else(|| Error::Protocol("client has not authenticated".into()))?;
        if self.pairings.key(&client.identifier) != Some(client.public_key) {
            return Err(Error::Protocol("pairing revoked".into()));
        }
        Ok(())
    }
    pub async fn tick(&mut self) -> Result<()> {
        if self.verified_client.is_none() {
            if self.setup_step > 0 && !self.pairings.is_open(self.pin) {
                return Err(Error::Protocol("pairing window closed".into()));
            }
            return Ok(());
        }
        self.check_authorised()?;
        if self.keyboard_subscribed {
            let current = self.delegate.keyboard_state();
            if current != self.keyboard_last {
                let event = if current.is_some() {
                    "_tiStarted"
                } else {
                    "_tiStopped"
                };
                let payload = keyboard::state_payload(current.as_ref())?;
                self.keyboard_last = current;
                self.keyboard_resync_state = None;
                self.send_event(event, None, Some(payload)).await?;
            }
        }
        Ok(())
    }

    pub fn is_established(&self) -> bool {
        self.session_established.load(Ordering::SeqCst)
    }

    pub fn timeout_deadline(&self) -> Arc<Mutex<Option<tokio::time::Instant>>> {
        self.timeout_deadline.clone()
    }

    pub fn device_id(&self) -> Option<String> {
        self.device_id.clone()
    }

    async fn write_raw(&self, bytes: &[u8]) -> Result<()> {
        let mut writer = self.writer.lock().await;
        tokio::time::timeout(std::time::Duration::from_secs(5), async {
            writer.write_all(bytes).await?;
            writer.flush().await
        })
        .await
        .map_err(|_| Error::Protocol("write timeout".into()))??;
        Ok(())
    }

    async fn send_frame(&self, frame_type: FrameType, payload: &[u8]) -> Result<()> {
        let mut out = Vec::with_capacity(4 + payload.len());
        out.push(frame_type as u8);
        out.extend_from_slice(&(payload.len() as u32).to_be_bytes()[1..]);
        out.extend_from_slice(payload);
        self.write_raw(&out).await
    }

    async fn send_opack(&self, frame_type: FrameType, data: &Value) -> Result<()> {
        let payload = opack::pack(data);
        debug!(">> {:?}", frame_type);
        self.send_frame(frame_type, &payload).await
    }

    async fn send_encrypted_opack(&mut self, data: &Value) -> Result<()> {
        let cipher = self
            .cipher
            .as_mut()
            .ok_or_else(|| Error::Protocol("encryption is not enabled".into()))?;
        let plain = opack::pack(data);
        let mut header = Vec::with_capacity(4);
        header.push(FrameType::EOpack as u8);
        header.extend_from_slice(&((plain.len() + 16) as u32).to_be_bytes()[1..]);
        let encrypted = cipher.encrypt(&plain, Some(&header), None)?;
        debug!(">> E_OPACK");
        let mut out = header;
        out.extend_from_slice(&encrypted);
        self.write_raw(&out).await
    }

    async fn send_response(
        &mut self,
        xid: Option<u64>,
        content: Option<Value>,
        result_type: u64,
    ) -> Result<()> {
        let mut response = vec![
            Value::kv("_c", content.unwrap_or(Value::dict(vec![]))),
            Value::kv("_rT", Value::Int(result_type)),
            Value::kv("_t", Value::Int(3)),
        ];
        if let Some(identifier) = &self.current_identifier {
            response.push(Value::kv("_i", Value::str(identifier)));
        }
        if let Some(xid) = xid {
            response.push(Value::kv("_x", Value::Int(xid)));
        }
        self.send_encrypted_opack(&Value::Dict(response)).await
    }

    async fn send_event(
        &mut self,
        identifier: &str,
        xid: Option<u64>,
        content: Option<Value>,
    ) -> Result<()> {
        let mut event = vec![
            Value::kv("_i", Value::str(identifier)),
            Value::kv("_t", Value::Int(1)),
            Value::kv("_c", content.unwrap_or(Value::dict(vec![]))),
        ];
        if let Some(xid) = xid {
            event.push(Value::kv("_x", Value::Int(xid)));
        }
        self.send_encrypted_opack(&Value::Dict(event)).await
    }

    /// Advertise volume support using both modern and legacy wire formats.
    async fn send_media_capabilities(&mut self, xid: Option<u64>) -> Result<()> {
        self.send_event(
            "MediaControlStatus",
            xid,
            Some(Value::dict(vec![Value::kv(
                "MediaControlFlags",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await?;
        self.send_event(
            "_iMC",
            xid,
            Some(Value::dict(vec![Value::kv(
                "_mcF",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await?;
        self.delegate
            .on_event(EventKind::Capabilities, "volume,mute,power");
        Ok(())
    }

    /// Re-publish the volume capability flag after a state change so iOS
    /// refreshes its volume HUD (it issues GetVolume in response).
    async fn push_media_state(&mut self, xid: Option<u64>) -> Result<()> {
        self.send_event(
            "MediaControlStatus",
            xid,
            Some(Value::dict(vec![Value::kv(
                "MediaControlFlags",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await?;
        self.send_event(
            "_iMC",
            xid,
            Some(Value::dict(vec![Value::kv(
                "_mcF",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await
    }

    /// Publish current power state using both known Companion event names.
    async fn send_power_state(&mut self, xid: Option<u64>) -> Result<()> {
        let content = Value::dict(vec![Value::kv("state", Value::Int(self.system_status))]);
        self.send_event("SystemStatus", xid, Some(content.clone()))
            .await?;
        self.send_event("TVSystemStatus", xid, Some(content))
            .await?;
        Ok(())
    }

    pub async fn handle_frame(
        &mut self,
        frame_type: FrameType,
        payload: &[u8],
        header: &[u8],
    ) -> Result<()> {
        if payload.len() > 64 * 1024 {
            return Err(Error::Protocol("frame limit".into()));
        }
        if self.rate_start.elapsed() > std::time::Duration::from_secs(1) {
            self.rate_start = std::time::Instant::now();
            self.rate_count = 0;
        }
        self.rate_count += 1;
        if self.rate_count > 300 {
            return Err(Error::Protocol("message rate limit".into()));
        }
        if frame_type == FrameType::Noop {
            if !payload.is_empty() {
                return Err(Error::Protocol("invalid keepalive".into()));
            }
            if self.verified_client.is_some() {
                self.check_authorised()?;
            }
            return Ok(());
        }
        if frame_type == FrameType::EOpack {
            self.check_authorised()?;
        } else if self.verified_client.is_some() {
            return Err(Error::Protocol("unexpected unencrypted frame".into()));
        }
        let mut payload = payload.to_vec();
        if frame_type == FrameType::EOpack {
            match self.cipher.as_mut() {
                Some(cipher) => {
                    payload = cipher.decrypt(&payload, Some(header), None)?;
                }
                None => {
                    return Err(Error::Protocol(
                        "encrypted frame before verification".into(),
                    ));
                }
            }
        }

        if payload.is_empty() {
            return Ok(());
        }

        let (data, remaining) = opack::unpack(&payload)?;
        if !remaining.is_empty() {
            return Err(Error::Protocol("trailing OPACK data".into()));
        }

        match frame_type {
            FrameType::PsStart | FrameType::PsNext | FrameType::PvStart | FrameType::PvNext => {
                self.handle_auth(frame_type, &data).await
            }
            FrameType::EOpack => {
                debug!("<< E_OPACK");
                self.handle_control(&data).await
            }
            other => {
                debug!("{:?}", other);
                Ok(())
            }
        }
    }

    async fn handle_auth(&mut self, frame_type: FrameType, data: &Value) -> Result<()> {
        let pairing_data = tlv::read_strict(
            data.get("_pd")
                .and_then(Value::as_bytes)
                .ok_or_else(|| Error::Protocol("missing pairing data".into()))?,
        )?;
        let seq = pairing_data
            .get(&tags::SEQ_NO)
            .filter(|v| v.len() == 1)
            .map(|v| v[0])
            .ok_or_else(|| Error::Protocol("invalid pairing sequence".into()))?;
        info!("<< {:?} seq={}", frame_type, seq);
        match (frame_type, seq) {
            (FrameType::PsNext, 3) => self
                .delegate
                .on_event(EventKind::PairSetupProofReceived, ""),
            (FrameType::PsNext, 5) => self
                .delegate
                .on_event(EventKind::PairSetupIdentityReceived, ""),
            (FrameType::PvNext, 3) => self
                .delegate
                .on_event(EventKind::PairVerifyIdentityReceived, ""),
            _ => {}
        }

        match (frame_type, seq) {
            (FrameType::PsStart, 1) => self.pair_setup_m1().await,
            (FrameType::PsNext, 3) => self.pair_setup_m3(&pairing_data).await,
            (FrameType::PsNext, 5) => self.pair_setup_m5(&pairing_data).await,
            (FrameType::PvStart, 1) => self.pair_verify_m1(&pairing_data).await,
            (FrameType::PvNext, 3) => self.pair_verify_m3(&pairing_data).await,
            _ => Err(Error::Protocol("unsupported auth sequence".into())),
        }
    }

    async fn pair_setup_m1(&mut self) -> Result<()> {
        if self.setup_step != 0 || self.verified_client.is_some() {
            return Err(Error::Protocol("unexpected pair-setup".into()));
        }
        // iOS probes the advertised identity with Pair-Verify M1, then may
        // start Pair-Setup on the same transport when it has no stored match.
        // A pending, unauthenticated verify transcript is not a permission.
        // Require the normal PIN window and attempt budget BEFORE retiring it.
        let pin = self.pairings.begin()?;
        if self.pending_verify.take().is_some() {
            self.verify_private = StaticSecret::random_from_rng(OsRng);
            self.verify_pub = X25519PublicKey::from(&self.verify_private);
            self.delegate
                .on_event(EventKind::PairVerifyReplacedBySetup, "");
        }
        self.pin = pin;
        *self.timeout_deadline.lock().await =
            Some(tokio::time::Instant::now() + std::time::Duration::from_secs(120));
        let mut ephemeral = [0u8; 32];
        OsRng.fill_bytes(&mut ephemeral);
        self.srp = SrpServer::new(self.pin, &ephemeral);
        self.setup_step = 2;
        let tlv_data = tlv::write(&[
            (tags::SEQ_NO, &[0x02]),
            (tags::SALT, self.srp.salt()),
            (tags::PUBLIC_KEY, &self.srp.public_key()),
            (27, &[0x01]),
        ]);
        self.inspector.emit("auth", "{\"step\":\"pair_setup_m1\"}");
        self.send_opack(
            FrameType::PsNext,
            &Value::dict(vec![
                Value::kv("_pd", Value::Bytes(tlv_data)),
                Value::kv("_pwTy", Value::Int(1)),
            ]),
        )
        .await
    }

    async fn pair_setup_m3(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        if self.setup_step != 2 || !self.pairings.is_open(self.pin) {
            return Err(Error::Protocol("unexpected or expired pair-setup".into()));
        }
        let public_key = pairing_data
            .get(&tags::PUBLIC_KEY)
            .ok_or_else(|| Error::Protocol("missing client public key".into()))?;
        self.srp.process(public_key)?;
        let proof_ok = pairing_data
            .get(&tags::PROOF)
            .map(|p| self.srp.verify_proof(p))
            .unwrap_or(false);
        self.inspector.emit(
            "auth",
            &format!("{{\"step\":\"pair_setup_m3\",\"verified\":{proof_ok}}}"),
        );
        self.setup_step = if proof_ok { 4 } else { 0 };
        let tlv_data = if proof_ok {
            tlv::write(&[
                (tags::SEQ_NO, &[0x04]),
                (tags::PROOF, &self.srp.key_proof_hash()?),
            ])
        } else {
            tlv::write(&[(tags::SEQ_NO, &[0x04]), (tags::ERROR, &[0x02])])
        };
        self.send_opack(
            FrameType::PsNext,
            &Value::dict(vec![Value::kv("_pd", Value::Bytes(tlv_data))]),
        )
        .await
    }

    async fn pair_setup_m5(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        if self.setup_step != 4 || !self.pairings.is_open(self.pin) {
            return Err(Error::Protocol("unverified pair-setup".into()));
        }
        let shared = self.srp.session_key()?.to_vec();
        let session_key = hkdf_expand(
            "Pair-Setup-Encrypt-Salt",
            "Pair-Setup-Encrypt-Info",
            &shared,
        );
        let mut chacha = CompanionCipher::new(&session_key, &session_key, 8)?;
        let encrypted = pairing_data
            .get(&tags::ENCRYPTED_DATA)
            .ok_or_else(|| Error::Protocol("missing encrypted data".into()))?;
        let decrypted = chacha.decrypt(encrypted, None, Some(b"PS-Msg05"))?;
        let client_tlv = tlv::read_strict(&decrypted)?;
        let identifier = std::str::from_utf8(
            client_tlv
                .get(&tags::IDENTIFIER)
                .ok_or_else(|| Error::Protocol("missing client identifier".into()))?,
        )
        .map_err(|_| Error::Protocol("invalid client identifier".into()))?
        .to_owned();
        validate_identifier(&identifier)?;
        let public_key: [u8; 32] = client_tlv
            .get(&tags::PUBLIC_KEY)
            .ok_or_else(|| Error::Protocol("missing client signing key".into()))?
            .as_slice()
            .try_into()
            .map_err(|_| Error::Protocol("invalid client signing key".into()))?;
        let controller_x = hkdf_expand(
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
            &shared,
        );
        let transcript = [
            controller_x.as_slice(),
            identifier.as_bytes(),
            public_key.as_slice(),
        ]
        .concat();
        verify_signature(
            &public_key,
            &transcript,
            client_tlv
                .get(&tags::SIGNATURE)
                .ok_or_else(|| Error::Protocol("missing client signature".into()))?,
        )?;
        self.pairings.authorise(
            PairedClient {
                identifier,
                public_key,
            },
            self.pin,
        )?;
        self.setup_step = 0;

        let accessory_x = hkdf_expand(
            "Pair-Setup-Accessory-Sign-Salt",
            "Pair-Setup-Accessory-Sign-Info",
            &shared,
        );
        let mut device_info = Vec::with_capacity(32 + self.unique_id.len() + 32);
        device_info.extend_from_slice(&accessory_x);
        device_info.extend_from_slice(&self.unique_id);
        device_info.extend_from_slice(&self.auth_pub);
        let signature = self.signing_key.sign(&device_info);

        let extra = Value::dict(vec![
            Value::kv(
                "altIRK",
                Value::Bytes(
                    hkdf_expand("CleanFrame identity", "altIRK", &self.private_key)[..16].to_vec(),
                ),
            ),
            Value::kv(
                "accountID",
                Value::str(&uuid::Uuid::new_v4().to_string().to_uppercase()),
            ),
            Value::kv("model", Value::str(DEVICE_MODEL)),
            Value::kv("wifiMAC", Value::Bytes(self.device_mac.to_vec())),
            Value::kv("name", Value::str(&self.device_name)),
            Value::kv("mac", Value::Bytes(self.device_mac.to_vec())),
        ]);
        let tlv_data = tlv::write(&[
            (tags::IDENTIFIER, &self.unique_id),
            (tags::PUBLIC_KEY, &self.auth_pub),
            (tags::SIGNATURE, &signature.to_bytes()),
            (17, &opack::pack(&extra)),
        ]);
        let mut chacha = CompanionCipher::new(&session_key, &session_key, 8)?;
        let encrypted = chacha.encrypt(&tlv_data, None, Some(b"PS-Msg06"))?;
        self.send_opack(
            FrameType::PsNext,
            &Value::dict(vec![Value::kv(
                "_pd",
                Value::Bytes(tlv::write(&[
                    (tags::SEQ_NO, &[0x06]),
                    (tags::ENCRYPTED_DATA, &encrypted),
                ])),
            )]),
        )
        .await?;
        info!("pairing complete");
        self.delegate.on_event(EventKind::Paired, "");
        self.inspector.emit("paired", "{}");
        // Fresh salt/verifier for the next pairing attempt.
        self.srp = SrpServer::new(0, &rand::random::<[u8; 32]>());
        Ok(())
    }

    async fn pair_verify_m1(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        if self.pending_verify.is_some() || self.setup_step != 0 {
            return Err(Error::Protocol("unexpected pair-verify".into()));
        }
        self.verify_private = StaticSecret::random_from_rng(OsRng);
        self.verify_pub = X25519PublicKey::from(&self.verify_private);
        let server_pub = self.verify_pub.to_bytes();
        let client_pub_bytes = pairing_data
            .get(&tags::PUBLIC_KEY)
            .ok_or_else(|| Error::Protocol("missing client public key".into()))?;
        let client_pub: [u8; 32] = client_pub_bytes
            .as_slice()
            .try_into()
            .map_err(|_| Error::Protocol("client public key must be 32 bytes".into()))?;
        let shared = self
            .verify_private
            .diffie_hellman(&X25519PublicKey::from(client_pub));
        if !shared.was_contributory() {
            return Err(Error::Crypto("invalid X25519 key".into()));
        }
        let session_key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            shared.as_bytes(),
        );

        let mut info = Vec::with_capacity(32 + self.unique_id.len() + 32);
        info.extend_from_slice(&server_pub);
        info.extend_from_slice(&self.unique_id);
        info.extend_from_slice(&client_pub);
        let signature = self.signing_key.sign(&info);

        let mut chacha = CompanionCipher::new(&session_key, &session_key, 8)?;
        let encrypted = chacha.encrypt(
            &tlv::write(&[
                (tags::IDENTIFIER, &self.unique_id),
                (tags::SIGNATURE, &signature.to_bytes()),
            ]),
            None,
            Some(b"PV-Msg02"),
        )?;

        self.pending_verify = Some((*shared.as_bytes(), client_pub));

        self.inspector.emit("auth", "{\"step\":\"pair_verify_m1\"}");

        self.send_opack(
            FrameType::PvNext,
            &Value::dict(vec![Value::kv(
                "_pd",
                Value::Bytes(tlv::write(&[
                    (tags::SEQ_NO, &[0x02]),
                    (tags::PUBLIC_KEY, &server_pub),
                    (tags::ENCRYPTED_DATA, &encrypted),
                ])),
            )]),
        )
        .await
    }

    async fn pair_verify_m3(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        let (shared, client_pub) = self
            .pending_verify
            .take()
            .ok_or_else(|| Error::Protocol("unexpected verify step".into()))?;
        let session_key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            &shared,
        );
        let mut cipher = CompanionCipher::new(&session_key, &session_key, 8)?;
        let encrypted = pairing_data
            .get(&tags::ENCRYPTED_DATA)
            .ok_or_else(|| Error::Protocol("missing verification data".into()))?;
        let decrypted = cipher.decrypt(encrypted, None, Some(b"PV-Msg03"))?;
        let verified = tlv::read_strict(&decrypted)?;
        let identifier = std::str::from_utf8(
            verified
                .get(&tags::IDENTIFIER)
                .ok_or_else(|| Error::Protocol("missing paired identifier".into()))?,
        )
        .map_err(|_| Error::Protocol("invalid paired identifier".into()))?
        .to_owned();
        let key = self
            .pairings
            .key(&identifier)
            .ok_or_else(|| Error::Protocol("unknown or revoked client".into()))?;
        let transcript = [
            client_pub.as_slice(),
            identifier.as_bytes(),
            self.verify_pub.as_bytes(),
        ]
        .concat();
        verify_signature(
            &key,
            &transcript,
            verified
                .get(&tags::SIGNATURE)
                .ok_or_else(|| Error::Protocol("missing verification signature".into()))?,
        )?;
        self.verified_client = Some(PairedClient {
            identifier,
            public_key: key,
        });
        self.cipher = Some(CompanionCipher::new(
            &hkdf_expand("", "ServerEncrypt-main", &shared),
            &hkdf_expand("", "ClientEncrypt-main", &shared),
            12,
        )?);
        self.send_opack(
            FrameType::PvNext,
            &Value::dict(vec![Value::kv(
                "_pd",
                Value::Bytes(tlv::write(&[(tags::SEQ_NO, &[0x04])])),
            )]),
        )
        .await?;
        info!("verification complete, encrypted control channel enabled");
        self.delegate.on_event(EventKind::Verified, "");
        self.inspector.emit("verified", "{}");

        // A verified peer has a bounded 30-second grace to start its session.
        // System-info probes must not shorten or refresh this deadline.
        *self.timeout_deadline.lock().await =
            Some(tokio::time::Instant::now() + std::time::Duration::from_secs(30));

        Ok(())
    }

    async fn handle_control(&mut self, data: &Value) -> Result<()> {
        // iOS 27 sends `_systemInfo` with the identifier in the `model` key
        // instead of `_i`; accept both so the `_systemInfo` arm below fires.
        let identifier = data
            .get("_i")
            .or_else(|| data.get("model"))
            .and_then(Value::as_str)
            .map(str::to_string);
        if identifier
            .as_ref()
            .is_some_and(|s| s.len() > 64 || s.chars().any(char::is_control))
        {
            return Err(Error::Protocol("control identifier limit".into()));
        }
        self.current_identifier = identifier.clone();
        self.delegate
            .on_event(EventKind::ControlMessage, "received");
        let xid = data.get("_x").and_then(Value::as_int);
        let content = data.get("_c").cloned().unwrap_or(Value::dict(vec![]));

        match identifier.as_deref() {
            Some("_sessionStart") => {
                if !self.session_established.swap(true, Ordering::SeqCst) {
                    self.inspector.session_started();
                }
                *self.timeout_deadline.lock().await = None;
                let local_sid = content
                    .get("_sid")
                    .or_else(|| content.get("sid"))
                    .and_then(Value::as_int)
                    .unwrap_or_else(|| rand::random::<u32>() as u64);
                let remote_sid = self.remote_session_id;
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "_sid",
                        Value::Int(remote_sid as u64),
                    )])),
                    0,
                )
                .await?;
                info!("<< _sessionStart: session established (local_sid={local_sid}, remote_sid={remote_sid})");
                self.delegate.on_event(
                    EventKind::SessionStarted,
                    &format!(
                        "local={} remote={} combined={}",
                        local_sid,
                        remote_sid,
                        ((remote_sid as u64) << 32) | local_sid
                    ),
                );
                self.inspector.emit(
                    "session_started",
                    &format!("{{\"local_sid\":{local_sid},\"remote_sid\":{remote_sid}}}"),
                );
            }
            Some("TVRCSessionStart") => {
                if !self.session_established.swap(true, Ordering::SeqCst) {
                    self.inspector.session_started();
                }
                *self.timeout_deadline.lock().await = None;
                let version = content
                    .get("ProtocolVersionKey")
                    .and_then(Value::as_str)
                    .unwrap_or("1.2")
                    .to_string();
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "ProtocolVersionKey",
                        Value::str(&version),
                    )])),
                    0,
                )
                .await?;
                // Current iOS versions make the volume/mute decision
                // immediately after session startup, so publish the flag
                // before subscriptions have necessarily arrived.
                self.send_media_capabilities(xid).await?;
                info!("<< TVRCSessionStart: remote ready");
                self.delegate.on_event(EventKind::RemoteReady, "");
                self.inspector.emit("remote_ready", "{}");
            }
            Some("_sessionStop") => {
                if self.session_established.swap(false, Ordering::SeqCst) {
                    self.inspector.session_stopped();
                }
                self.send_response(xid, None, 0).await?;
                info!("<< _sessionStop: remote session closed");
                self.delegate.on_event(EventKind::SessionStopped, "");
                self.inspector.emit("session_stopped", "{}");
            }
            Some("_hidC") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.handle_hid_command(&content, xid).await?;
            }
            Some("_touchStart") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv("_i", Value::Int(1))])),
                    0,
                )
                .await?;
            }
            Some("_hidT") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.send_response(xid, None, 0).await?;
                self.handle_touch(&content);
            }
            Some("_touchStop") | Some("_touchMove") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.send_response(xid, None, 0).await?;
            }
            Some("_interest") => {
                // _interest itself is an event and does not need a response.
                // A real Apple TV pushes the current state for each newly
                // registered event.
                if let Some(Value::Array(events)) = content.get("_regEvents") {
                    let names: Vec<String> = events
                        .iter()
                        .filter_map(|e| e.as_str().map(str::to_string))
                        .collect();
                    for event_name in names {
                        match event_name.as_str() {
                            "MediaControlStatus" => {
                                self.send_event(
                                    &event_name,
                                    xid,
                                    Some(Value::dict(vec![Value::kv(
                                        "MediaControlFlags",
                                        Value::Int(MEDIA_CONTROL_VOLUME),
                                    )])),
                                )
                                .await?;
                            }
                            "NowPlayingInfo" => {
                                self.send_event(&event_name, xid, None).await?;
                            }
                            "SystemStatus" | "TVSystemStatus" => {
                                self.send_event(
                                    &event_name,
                                    xid,
                                    Some(Value::dict(vec![Value::kv(
                                        "state",
                                        Value::Int(self.system_status),
                                    )])),
                                )
                                .await?;
                            }
                            _ => {}
                        }
                    }
                }
            }
            Some("FetchMediaControlStatus") => {
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "MediaControlFlags",
                        Value::Int(MEDIA_CONTROL_VOLUME),
                    )])),
                    0,
                )
                .await?;
            }
            Some("FetchSiriRemoteInfo") | Some("FetchCurrentNowPlayingInfoEvent") => {
                self.send_response(xid, None, 0).await?;
            }
            Some("MediaControlCommand") | Some("_mcc") => {
                self.handle_media_control(&content, xid).await?;
            }
            Some("_tiStart") => {
                self.delegate
                    .on_event(EventKind::TextInputStartReceived, "");
                self.keyboard_subscribed = true;
                self.keyboard_resync_state = None;
                let current = self.delegate.keyboard_state();
                let payload = keyboard::state_payload(current.as_ref())?;
                self.keyboard_last = current;
                self.send_response(xid, Some(payload), 0).await?;
            }
            Some("_tiStop") => {
                self.delegate.on_event(EventKind::TextInputStopReceived, "");
                self.keyboard_subscribed = false;
                self.keyboard_last = None;
                self.keyboard_resync_state = None;
                self.send_response(xid, None, 0).await?;
            }
            Some("_tiC") => {
                self.delegate.on_event(EventKind::TextInputEditReceived, "");
                let request = data.get("_t").and_then(Value::as_int) == Some(2);
                let result = match self.delegate.keyboard_state() {
                    None => Err(EventKind::KeyboardNoField),
                    Some(current) => match content.get("_tiD") {
                        None => Err(EventKind::KeyboardMissingData),
                        Some(value) => match value.as_bytes() {
                            None => Err(EventKind::KeyboardDataType),
                            Some(bytes) => keyboard::decode_edit(bytes, &current)
                                .map_err(keyboard_rejection_event),
                        },
                    },
                };
                match result {
                    Ok(edit) => {
                        if self.delegate.keyboard_edit(edit) {
                            if request {
                                self.send_response(xid, None, 0).await?;
                            }
                        } else {
                            self.reject_keyboard(xid, request, EventKind::KeyboardFieldChanged)
                                .await?;
                        }
                    }
                    Err(reason) => {
                        if let Some(bytes) = content.get("_tiD").and_then(Value::as_bytes) {
                            for shape in self.keyboard_shapes.record(bytes) {
                                self.delegate
                                    .on_event(EventKind::KeyboardStructure(shape), "");
                            }
                        }
                        self.reject_keyboard(xid, request, reason).await?;
                    }
                }
            }
            Some("FetchLaunchableApplicationsEvent") => {
                self.send_response(
                    xid,
                    Some(Value::dict(vec![
                        Value::kv("com.apple.TVAppStore", Value::str("App Store")),
                        Value::kv("com.apple.TVSettings", Value::str("Settings")),
                        Value::kv("com.apple.TVMusic", Value::str("Music")),
                        Value::kv("com.apple.TVMovies", Value::str("Movies")),
                        Value::kv("com.apple.TVWatchList", Value::str("TV")),
                        Value::kv("com.google.ios.youtube", Value::str("YouTube")),
                        Value::kv("com.netflix.Netflix", Value::str("Netflix")),
                    ])),
                    2,
                )
                .await?;
            }
            Some("FetchAttentionState") => {
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "state",
                        Value::Int(self.system_status),
                    )])),
                    0,
                )
                .await?;
            }
            Some("_launchApp") => {
                let _bundle_id = content
                    .get("_bundleID")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string();
                self.delegate.on_event(EventKind::LaunchApp, "unsupported");
                self.send_response(xid, None, 0).await?;
            }
            Some("_systemInfo") | Some("SystemInfo") => {
                let ids_id = content.get("_idsID").and_then(Value::as_str);
                let pub_id = content.get("_pubID").and_then(Value::as_str);
                let name = content.get("name").and_then(Value::as_str).unwrap_or("");
                let model = content.get("model").and_then(Value::as_str).unwrap_or("");

                for field in [ids_id, pub_id, Some(name), Some(model)]
                    .into_iter()
                    .flatten()
                {
                    if field.len() > 128 || field.chars().any(char::is_control) {
                        return Err(Error::Protocol("device metadata limit".into()));
                    }
                }
                let primary_id = ids_id.or(pub_id).unwrap_or(&self.peer_ip);
                let alias_id = if ids_id.is_some() { pub_id } else { None };

                let (session_id, is_renewed) = {
                    let mut reg = self.registry.lock().await;
                    reg.get_or_register(primary_id, alias_id, name, model)
                };
                self.remote_session_id = session_id;
                self.device_id = Some(primary_id.to_string());

                {
                    let mut tracker = self.active_tracker.lock().await;
                    tracker.set_device_id(self.conn_id, primary_id);
                }

                self.send_response(xid, None, 0).await?;
                info!("<< _systemInfo: replied and refreshed power state (session_id={session_id}, renewed={is_renewed})");
                self.send_power_state(None).await?;

                // An XID correlates a request; its high bit does not prove
                // whether iOS will subsequently establish a Remote session.
                // Preserve the fixed verified-session grace instead of the
                // legacy 1s/2.5s heuristic, and never extend it on metadata.
                let high_transaction_id = xid.is_some_and(|x| (x & 0x8000_0000) != 0);
                self.delegate.on_event(
                    if high_transaction_id {
                        EventKind::SystemInfoHighTransactionId
                    } else {
                        EventKind::SystemInfoLowTransactionId
                    },
                    "",
                );
            }
            _ => {
                if identifier.is_some() {
                    self.delegate
                        .on_event(EventKind::UnknownControlReceived, "");
                    debug!("unhandled control message");
                }
                if xid.is_some() {
                    self.send_response(xid, None, 0).await?;
                }
            }
        }
        Ok(())
    }

    async fn reject_keyboard(
        &mut self,
        xid: Option<u64>,
        request: bool,
        reason: EventKind,
    ) -> Result<()> {
        self.delegate.on_event(reason, "");
        if request {
            // Companion error envelope: _em denotes a failed response. Avoid
            // inventing an Apple numeric error code for a local field state.
            let mut error = vec![
                Value::kv("_i", Value::str("_tiC")),
                Value::kv("_t", Value::Int(3)),
                Value::kv("_em", Value::str("Keyboard input unavailable")),
            ];
            if let Some(xid) = xid {
                error.push(Value::kv("_x", Value::Int(xid)));
            }
            self.send_encrypted_opack(&Value::Dict(error)).await?;
        }
        if self.keyboard_subscribed {
            let current = self.delegate.keyboard_state();
            // Repeating an unchanged _tiStarted for each rejected notification
            // can trigger another client notification. Refresh once per state.
            if self.keyboard_resync_state.as_ref() == Some(&current) {
                return Ok(());
            }
            self.keyboard_resync_state = Some(current.clone());
            let payload = keyboard::state_payload(current.as_ref())?;
            let identifier = if current.is_some() {
                "_tiStarted"
            } else {
                "_tiStopped"
            };
            self.keyboard_last = current;
            self.send_event(identifier, None, Some(payload)).await?;
        }
        Ok(())
    }

    async fn handle_hid_command(&mut self, content: &Value, xid: Option<u64>) -> Result<()> {
        let command = content.get("_hidC").and_then(Value::as_int).unwrap_or(0);
        let state = content.get("_hBtS").and_then(Value::as_int).unwrap_or(0);
        let command_name = hid_command_name(command);
        self.send_response(xid, None, 0).await?;

        if state == 2 {
            match command_name.as_str() {
                "volume_up" | "volume_down" => {
                    self.delegate.on_button(&command_name);
                    if let Some((vol, muted)) = self.delegate.get_audio_state() {
                        self.volume = vol;
                        self.muted = muted;
                    } else {
                        let step = 1.0 / 16.0;
                        if command_name == "volume_up" {
                            self.volume = (self.volume + step).min(1.0);
                        } else {
                            self.volume = (self.volume - step).max(0.0);
                        }
                        self.muted = false;
                        self.delegate.on_audio(self.volume, self.muted);
                    }
                    self.inspector.emit(
                        "audio_state",
                        &format!("{{\"volume\":{:.3},\"muted\":{}}}", self.volume, self.muted),
                    );
                    self.push_media_state(xid).await?;
                }
                "mute" => {
                    self.delegate.on_button(&command_name);
                    if let Some((vol, muted)) = self.delegate.get_audio_state() {
                        self.volume = vol;
                        self.muted = muted;
                    } else {
                        self.muted = !self.muted;
                        self.delegate.on_audio(self.volume, self.muted);
                    }
                    self.inspector.emit(
                        "audio_state",
                        &format!("{{\"volume\":{:.3},\"muted\":{}}}", self.volume, self.muted),
                    );
                    self.send_event(
                        "MediaControlStatus",
                        xid,
                        Some(Value::dict(vec![
                            Value::kv("MediaControlFlags", Value::Int(MEDIA_CONTROL_VOLUME)),
                            Value::kv("Muted", Value::Bool(self.muted)),
                        ])),
                    )
                    .await?;
                    self.send_event(
                        "_iMC",
                        xid,
                        Some(Value::dict(vec![Value::kv(
                            "_mcF",
                            Value::Int(MEDIA_CONTROL_VOLUME),
                        )])),
                    )
                    .await?;
                }
                "power" => {
                    self.display_power_on = !self.display_power_on;
                    self.delegate.on_event(
                        EventKind::PowerState,
                        if self.display_power_on { "on" } else { "off" },
                    );
                    self.inspector.emit(
                        "power_state",
                        &format!("{{\"display_on\":{}}}", self.display_power_on),
                    );
                    self.delegate.on_button(&command_name);
                }
                _ => {
                    self.delegate.on_button(&command_name);
                }
            }
            self.inspector
                .emit("button", &format!("{{\"name\":\"{command_name}\"}}"));
        }
        Ok(())
    }

    async fn handle_media_control(&mut self, content: &Value, xid: Option<u64>) -> Result<()> {
        let command = content
            .get("MediaControlCommand")
            .or_else(|| content.get("_mcc"))
            .and_then(Value::as_int);
        let mut response = Value::dict(vec![]);
        match command {
            // GetVolume: report 0 while muted so the phone's volume HUD shows
            // an empty bar — the only visual mute feedback this protocol has.
            Some(5) => {
                if let Some((vol, muted)) = self.delegate.get_audio_state() {
                    self.volume = vol;
                    self.muted = muted;
                }
                response = Value::dict(vec![Value::kv(
                    "_vol",
                    Value::Double(if self.muted { 0.0 } else { self.volume }),
                )]);
            }
            // SetVolume
            Some(6) => {
                if let Some(requested) = content.get("_vol").and_then(Value::as_float) {
                    self.volume = requested.clamp(0.0, 1.0);
                    // Raising the level from the phone lifts the mute.
                    if self.volume > 0.0 {
                        self.muted = false;
                    }
                    self.delegate.on_audio(self.volume, self.muted);
                    self.inspector.emit(
                        "audio_state",
                        &format!("{{\"volume\":{:.3},\"muted\":{}}}", self.volume, self.muted),
                    );
                }
            }
            // GetCaptionSettings
            Some(12) => {
                response = Value::dict(vec![Value::kv("_cse", Value::Bool(false))]);
            }
            _ => {}
        }
        self.send_response(xid, Some(response), 0).await
    }

    /// Translate touchpad gestures into directional button presses (direction mode),
    /// pointer-style deltas (mouse mode), or trajectory preview only (idle mode).
    fn handle_touch(&mut self, content: &Value) {
        let phase = content.get("_tPh").and_then(Value::as_int).unwrap_or(0);
        let x = content.get("_cx").and_then(Value::as_float);
        let y = content.get("_cy").and_then(Value::as_float);

        let mode_u8 = self.trackpad_mode.load(Ordering::SeqCst);
        let mode = TrackpadMode::from_u8(mode_u8);

        if phase == 1 {
            // touch began
            if let (Some(x), Some(y)) = (x, y) {
                self.touch_origin = Some((x, y));
                self.touch_last = Some((x, y));
                self.touch_moved = false;
                if mode == TrackpadMode::Mouse {
                    self.delegate.on_touch(0.0, 0.0, TouchPhase::Began);
                }
                self.inspector
                    .emit("touch", "{\"phase\":1,\"dx\":0.0,\"dy\":0.0}");
            }
            return;
        }
        let (Some(origin), Some(x), Some(y)) = (self.touch_origin, x, y) else {
            return;
        };
        let position = (x, y);

        if phase != 4 {
            // intermediate move
            if let Some(last) = self.touch_last {
                let dx = position.0 - last.0;
                let dy = position.1 - last.1;
                if dx != 0.0 || dy != 0.0 {
                    if mode == TrackpadMode::Mouse {
                        self.touch_moved = true;
                        self.delegate.on_touch(dx, dy, TouchPhase::Moved);
                    }
                    self.inspector.emit(
                        "touch",
                        &format!("{{\"phase\":2,\"dx\":{:.1},\"dy\":{:.1}}}", dx, dy),
                    );
                }
            }
            self.touch_last = Some(position);
            return;
        }

        // phase == 4: finger lifted
        self.touch_origin = None;
        self.touch_last = None;
        let dx = position.0 - origin.0;
        let dy = position.1 - origin.1;
        let distance = dx.abs().max(dy.abs());

        if mode == TrackpadMode::Mouse {
            self.delegate.on_touch(dx, dy, TouchPhase::Ended);
        }
        self.inspector.emit(
            "touch",
            &format!("{{\"phase\":4,\"dx\":{:.1},\"dy\":{:.1}}}", dx, dy),
        );

        // In Idle mode: only emit touch trajectory for real-time preview, do not trigger any actions
        if mode == TrackpadMode::Idle {
            return;
        }

        if mode == TrackpadMode::Mouse {
            if distance < TAP_MAX_DISTANCE {
                self.delegate.on_event(EventKind::MouseClick, "");
                self.inspector.emit("button", "{\"name\":\"click\"}");
            }
            return;
        }

        if distance < TAP_MAX_DISTANCE {
            self.delegate.on_button("select");
            self.inspector.emit("button", "{\"name\":\"select\"}");
            return;
        }

        if distance < SWIPE_MIN_DISTANCE {
            return;
        }
        let direction = if dx.abs() > dy.abs() {
            if dx > 0.0 {
                "right"
            } else {
                "left"
            }
        } else if dy > 0.0 {
            "down"
        } else {
            "up"
        };
        self.delegate.on_button(direction);
        self.inspector
            .emit("button", &format!("{{\"name\":\"{direction}\"}}"));
    }
}

fn keyboard_rejection_event(reason: keyboard::KeyboardRejection) -> EventKind {
    use keyboard::KeyboardRejection;
    match reason {
        KeyboardRejection::Malformed => EventKind::KeyboardMalformedData,
        KeyboardRejection::Encoding => EventKind::KeyboardInvalidEncoding,
        KeyboardRejection::BinaryPlistVersion => EventKind::KeyboardBinaryPlistVersion,
        KeyboardRejection::XmlEncoding => EventKind::KeyboardXmlEncoding,
        KeyboardRejection::OperationType => EventKind::KeyboardOperationType,
        KeyboardRejection::TargetMissing => EventKind::KeyboardTargetMissing,
        KeyboardRejection::TargetType => EventKind::KeyboardTargetType,
        KeyboardRejection::TargetData => EventKind::KeyboardTargetData,
        KeyboardRejection::TargetString => EventKind::KeyboardTargetString,
        KeyboardRejection::UuidMissing => EventKind::KeyboardUuidMissing,
        KeyboardRejection::OutputMissing => EventKind::KeyboardOutputMissing,

        KeyboardRejection::PlistDecode => EventKind::KeyboardPlistDecode,
        KeyboardRejection::ArchiveRoot => EventKind::KeyboardArchiveRoot,
        KeyboardRejection::ArchiveObjects => EventKind::KeyboardArchiveObjects,
        KeyboardRejection::ArchiveTop => EventKind::KeyboardArchiveTop,
        KeyboardRejection::ArchiveReference => EventKind::KeyboardArchiveReference,
        KeyboardRejection::TargetUuid => EventKind::KeyboardTargetUuid,
        KeyboardRejection::AssertionType => EventKind::KeyboardAssertionType,
        KeyboardRejection::OutputType => EventKind::KeyboardOutputType,
        KeyboardRejection::InsertionType => EventKind::KeyboardInsertionType,
        KeyboardRejection::EmptyOperation => EventKind::KeyboardEmptyOperation,
        KeyboardRejection::Limit => EventKind::KeyboardLimit,
        KeyboardRejection::UnsupportedArchive => EventKind::KeyboardUnsupportedArchive,
        KeyboardRejection::StaleSession => EventKind::KeyboardStaleSession,
        KeyboardRejection::TextMismatch => EventKind::KeyboardTextMismatch,
        KeyboardRejection::UnsupportedOperation => EventKind::KeyboardUnsupportedOperation,
    }
}

impl Drop for CompanionSession {
    fn drop(&mut self) {
        if self.session_established.load(Ordering::SeqCst) {
            self.inspector.session_stopped();
        }
    }
}

#[cfg(test)]
mod auth_tests {
    use super::*;
    use std::{collections::BTreeMap, time::Duration};
    use tokio::net::{TcpListener, TcpStream};
    struct Delegate;
    impl AtvDelegate for Delegate {}
    async fn fixture(initial: Vec<PairedClient>) -> (CompanionSession, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (server, _) = listener.accept().await.unwrap();
        let (_, writer) = server.into_split();
        let identity = DeviceIdentity {
            name: "test".into(),
            device_id: "02:00:00:00:00:01".into(),
            mac_bytes: [2, 0, 0, 0, 0, 1],
            server_identifier: "server".into(),
            unique_id: "server".into(),
            private_key: [3; 32],
        };
        let session = CompanionSession::new(
            Arc::new(Mutex::new(writer)),
            Arc::new(Delegate),
            &identity,
            Arc::new(PairingStore::new(initial, Arc::new(|_| Ok(()))).unwrap()),
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicU8::new(0)),
            InspectorHub::new(),
            Arc::new(Mutex::new(DeviceRegistry::new(Duration::from_secs(60)))),
            Arc::new(Mutex::new(ActiveConnectionTracker::new())),
            1,
            "127.0.0.1".into(),
            Arc::new(AtomicBool::new(false)),
        );
        (session, client)
    }
    async fn verify_client(
        session: &mut CompanionSession,
        signing: &SigningKey,
        identifier: &str,
        corrupt: bool,
    ) -> Result<CompanionCipher> {
        let ephemeral = StaticSecret::random_from_rng(OsRng);
        let public = X25519PublicKey::from(&ephemeral);
        session
            .pair_verify_m1(&BTreeMap::from([(
                tags::PUBLIC_KEY,
                public.to_bytes().to_vec(),
            )]))
            .await?;
        assert!(session.cipher.is_none(), "M1 never enables control");
        let shared = ephemeral.diffie_hellman(&session.verify_pub);
        let transcript = [
            public.as_bytes().as_slice(),
            identifier.as_bytes(),
            session.verify_pub.as_bytes(),
        ]
        .concat();
        let mut sig = signing.sign(&transcript).to_bytes();
        if corrupt {
            sig[0] ^= 1;
        }
        let tlv = tlv::write(&[
            (tags::IDENTIFIER, identifier.as_bytes()),
            (tags::SIGNATURE, &sig),
        ]);
        let key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            shared.as_bytes(),
        );
        let encrypted =
            CompanionCipher::new(&key, &key, 8)?.encrypt(&tlv, None, Some(b"PV-Msg03"))?;
        session
            .pair_verify_m3(&BTreeMap::from([(tags::ENCRYPTED_DATA, encrypted)]))
            .await?;
        CompanionCipher::new(
            &hkdf_expand("", "ClientEncrypt-main", shared.as_bytes()),
            &hkdf_expand("", "ServerEncrypt-main", shared.as_bytes()),
            12,
        )
    }
    async fn verify(
        session: &mut CompanionSession,
        signing: &SigningKey,
        identifier: &str,
        corrupt: bool,
    ) -> Result<()> {
        verify_client(session, signing, identifier, corrupt)
            .await
            .map(|_| ())
    }
    #[tokio::test]
    async fn valid_verify_then_revocation_blocks_next_command() {
        let key = SigningKey::from_bytes(&[7; 32]);
        let (mut s, _peer) = fixture(vec![PairedClient {
            identifier: "phone".into(),
            public_key: key.verifying_key().to_bytes(),
        }])
        .await;
        verify(&mut s, &key, "phone", false).await.unwrap();
        assert!(s.check_authorised().is_ok());
        s.pairings.revoke("phone").unwrap();
        assert!(s.tick().await.is_err());
        assert!(s
            .handle_frame(FrameType::EOpack, &[0; 16], &[0; 4])
            .await
            .is_err());
    }
    #[tokio::test]
    async fn unknown_and_invalid_signatures_never_enable_control() {
        let key = SigningKey::from_bytes(&[7; 32]);
        for (known, corrupt) in [(false, false), (true, true)] {
            let clients = if known {
                vec![PairedClient {
                    identifier: "phone".into(),
                    public_key: key.verifying_key().to_bytes(),
                }]
            } else {
                vec![]
            };
            let (mut s, _peer) = fixture(clients).await;
            assert!(verify(&mut s, &key, "phone", corrupt).await.is_err());
            assert!(s.cipher.is_none());
            assert!(s.check_authorised().is_err());
        }
    }
    #[tokio::test]
    async fn zero_x25519_and_unauthenticated_control_rejected() {
        let (mut s, _peer) = fixture(vec![]).await;
        assert!(s
            .handle_frame(FrameType::EOpack, &[0; 16], &[0; 4])
            .await
            .is_err());
        assert!(s
            .pair_verify_m1(&BTreeMap::from([(tags::PUBLIC_KEY, vec![0; 32])]))
            .await
            .is_err());
        assert!(s.pair_setup_m5(&BTreeMap::new()).await.is_err());
    }
    #[tokio::test]
    async fn setup_m5_requires_real_signature_before_persistence() {
        for corrupt in [true, false] {
            let (mut s, _peer) = fixture(vec![]).await;
            s.pin = s.pairings.open_pairing(Duration::from_secs(60));
            s.setup_step = 4;
            s.srp = SrpServer::new(s.pin, &[4; 32]);
            s.srp.process(&[5]).unwrap();
            let shared = s.srp.session_key().unwrap();
            let signing = SigningKey::from_bytes(&[7; 32]);
            let public = signing.verifying_key().to_bytes();
            let x = hkdf_expand(
                "Pair-Setup-Controller-Sign-Salt",
                "Pair-Setup-Controller-Sign-Info",
                shared,
            );
            let mut sig = signing
                .sign(&[x.as_slice(), b"phone", public.as_slice()].concat())
                .to_bytes();
            if corrupt {
                sig[0] ^= 1;
            }
            let tlv = tlv::write(&[
                (tags::IDENTIFIER, b"phone"),
                (tags::PUBLIC_KEY, &public),
                (tags::SIGNATURE, &sig),
            ]);
            let key = hkdf_expand("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", shared);
            let encrypted = CompanionCipher::new(&key, &key, 8)
                .unwrap()
                .encrypt(&tlv, None, Some(b"PS-Msg05"))
                .unwrap();
            let result = s
                .pair_setup_m5(&BTreeMap::from([(tags::ENCRYPTED_DATA, encrypted)]))
                .await;
            assert_eq!(result.is_err(), corrupt);
            assert_eq!(s.pairings.list().len(), usize::from(!corrupt));
            if !corrupt {
                assert!(!s.pairings.is_open(s.pin));
            }
        }
    }
    #[tokio::test]
    async fn two_session_start_messages_count_as_one_live_session() {
        let key = SigningKey::from_bytes(&[7; 32]);
        let (mut s, _peer) = fixture(vec![PairedClient {
            identifier: "phone".into(),
            public_key: key.verifying_key().to_bytes(),
        }])
        .await;
        verify(&mut s, &key, "phone", false).await.unwrap();
        for name in ["_sessionStart", "TVRCSessionStart", "_sessionStop"] {
            s.handle_control(&Value::dict(vec![
                Value::kv("_i", Value::str(name)),
                Value::kv("_x", Value::Int(1)),
            ]))
            .await
            .unwrap();
        }
        assert!(!s.inspector.get_client_info().2);
    }
    async fn auth_frame(
        session: &mut CompanionSession,
        frame: FrameType,
        seq: u8,
        extra: &[(u8, &[u8])],
    ) -> Result<()> {
        let mut entries = vec![(tags::SEQ_NO, std::slice::from_ref(&seq))];
        entries.extend_from_slice(extra);
        let payload = opack::pack(&Value::dict(vec![Value::kv(
            "_pd",
            Value::Bytes(tlv::write(&entries)),
        )]));
        let mut header = [frame as u8, 0, 0, 0];
        header[1..].copy_from_slice(&(payload.len() as u32).to_be_bytes()[1..]);
        session.handle_frame(frame, &payload, &header).await
    }
    #[tokio::test]
    async fn verify_probe_can_fall_back_to_pin_setup_on_same_connection() {
        let (mut session, _peer) = fixture(vec![]).await;
        session.pairings.open_pairing(Duration::from_secs(120));
        let client = StaticSecret::random_from_rng(OsRng);
        let public = X25519PublicKey::from(&client).to_bytes();
        auth_frame(
            &mut session,
            FrameType::PvStart,
            1,
            &[(tags::PUBLIC_KEY, &public)],
        )
        .await
        .unwrap();
        assert!(session.pending_verify.is_some());
        let old_public = session.verify_pub.to_bytes();
        auth_frame(&mut session, FrameType::PsStart, 1, &[])
            .await
            .unwrap();
        assert_eq!(session.setup_step, 2);
        assert!(session.pending_verify.is_none());
        assert_ne!(session.verify_pub.to_bytes(), old_public);
        assert!(session.cipher.is_none());
        assert!(session.pairings.list().is_empty());
        assert!(session.check_authorised().is_err());
        // Neither a late verify proof nor a skipped SRP proof can authorise.
        assert!(auth_frame(
            &mut session,
            FrameType::PvNext,
            3,
            &[(tags::ENCRYPTED_DATA, &[1; 32])]
        )
        .await
        .is_err());
        assert!(auth_frame(
            &mut session,
            FrameType::PsNext,
            5,
            &[(tags::ENCRYPTED_DATA, &[1; 32])]
        )
        .await
        .is_err());
        assert!(session.cipher.is_none());
        assert!(session.pairings.list().is_empty());
    }
    #[tokio::test]
    async fn verify_fallback_requires_open_window_and_keeps_attempt_limit() {
        let (mut session, _peer) = fixture(vec![]).await;
        assert!(auth_frame(&mut session, FrameType::PsStart, 1, &[])
            .await
            .is_err());
        assert_eq!(session.setup_step, 0);
        let client = StaticSecret::random_from_rng(OsRng);
        let public = X25519PublicKey::from(&client).to_bytes();
        auth_frame(
            &mut session,
            FrameType::PvStart,
            1,
            &[(tags::PUBLIC_KEY, &public)],
        )
        .await
        .unwrap();
        assert!(auth_frame(&mut session, FrameType::PsStart, 1, &[])
            .await
            .is_err());
        assert!(session.pending_verify.is_some());
        assert_eq!(session.setup_step, 0);
        session.pairings.open_pairing(Duration::ZERO);
        assert!(auth_frame(&mut session, FrameType::PsStart, 1, &[])
            .await
            .is_err());
        session.pairings.open_pairing(Duration::from_secs(120));
        for _ in 0..5 {
            session.pairings.begin().unwrap();
        }
        assert!(
            matches!(auth_frame(&mut session,FrameType::PsStart,1,&[]).await,Err(Error::Protocol(message)) if message=="pairing attempt limit")
        );
        assert!(session.pending_verify.is_some());
        assert_eq!(session.setup_step, 0);
        assert!(session.cipher.is_none());
        assert!(session.pairings.list().is_empty());
    }
    #[tokio::test]
    async fn setup_cannot_reset_an_active_setup_or_verified_connection() {
        let (mut session, _peer) = fixture(vec![]).await;
        session.pairings.open_pairing(Duration::from_secs(120));
        auth_frame(&mut session, FrameType::PsStart, 1, &[])
            .await
            .unwrap();
        let salt = session.srp.salt().to_vec();
        let public = session.srp.public_key();
        assert!(auth_frame(&mut session, FrameType::PsStart, 1, &[])
            .await
            .is_err());
        assert_eq!(session.srp.salt(), salt);
        assert_eq!(session.srp.public_key(), public);
        let key = SigningKey::from_bytes(&[7; 32]);
        let (mut verified, _peer) = fixture(vec![PairedClient {
            identifier: "phone".into(),
            public_key: key.verifying_key().to_bytes(),
        }])
        .await;
        verify(&mut verified, &key, "phone", false).await.unwrap();
        verified.pairings.open_pairing(Duration::from_secs(120));
        assert!(auth_frame(&mut verified, FrameType::PsStart, 1, &[])
            .await
            .is_err());
        assert!(verified.check_authorised().is_ok());
    }
    #[tokio::test]
    async fn cryptographically_valid_old_verify_proof_cannot_resume_after_setup_fallback() {
        let signing = SigningKey::from_bytes(&[7; 32]);
        let (mut session, _peer) = fixture(vec![PairedClient {
            identifier: "phone".into(),
            public_key: signing.verifying_key().to_bytes(),
        }])
        .await;
        let ephemeral = StaticSecret::random_from_rng(OsRng);
        let public = X25519PublicKey::from(&ephemeral);
        auth_frame(
            &mut session,
            FrameType::PvStart,
            1,
            &[(tags::PUBLIC_KEY, public.as_bytes())],
        )
        .await
        .unwrap();
        let shared = ephemeral.diffie_hellman(&session.verify_pub);
        let signature = signing.sign(
            &[
                public.as_bytes().as_slice(),
                b"phone",
                session.verify_pub.as_bytes(),
            ]
            .concat(),
        );
        let data = tlv::write(&[
            (tags::IDENTIFIER, b"phone"),
            (tags::SIGNATURE, &signature.to_bytes()),
        ]);
        let key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            shared.as_bytes(),
        );
        let encrypted = CompanionCipher::new(&key, &key, 8)
            .unwrap()
            .encrypt(&data, None, Some(b"PV-Msg03"))
            .unwrap();
        session.pairings.open_pairing(Duration::from_secs(120));
        auth_frame(&mut session, FrameType::PsStart, 1, &[])
            .await
            .unwrap();
        assert!(
            matches!(auth_frame(&mut session,FrameType::PvNext,3,&[(tags::ENCRYPTED_DATA,&encrypted)]).await,Err(Error::Protocol(message)) if message=="unexpected verify step")
        );
        assert!(session.cipher.is_none());
        assert!(session.verified_client.is_none());
        assert_eq!(session.setup_step, 2);
        assert_eq!(session.pairings.list().len(), 1);
    }
    struct TextDelegate {
        field: Option<KeyboardState>,
        accepts: bool,
        events: std::sync::Mutex<Vec<EventKind>>,
        buttons: std::sync::Mutex<Vec<String>>,
    }
    impl AtvDelegate for TextDelegate {
        fn keyboard_state(&self) -> Option<KeyboardState> {
            self.field.clone()
        }
        fn keyboard_edit(&self, _: keyboard::KeyboardEdit) -> bool {
            self.accepts
        }
        fn on_event(&self, event: EventKind, _: &str) {
            self.events.lock().unwrap().push(event);
        }
        fn on_button(&self, button: &str) {
            self.buttons.lock().unwrap().push(button.into());
        }
    }
    async fn peer_frame(peer: &mut TcpStream) -> ([u8; 4], Vec<u8>) {
        use tokio::io::AsyncReadExt;
        let mut header = [0u8; 4];
        peer.read_exact(&mut header).await.unwrap();
        let len = u32::from_be_bytes([0, header[1], header[2], header[3]]) as usize;
        assert!(len < 65536);
        let mut payload = vec![0u8; len];
        peer.read_exact(&mut payload).await.unwrap();
        (header, payload)
    }
    async fn authenticated_input(
        session: &mut CompanionSession,
        client: &mut CompanionCipher,
        data: Value,
    ) -> Result<()> {
        let plain = opack::pack(&data);
        let mut header = [FrameType::EOpack as u8, 0, 0, 0];
        header[1..].copy_from_slice(&((plain.len() + 16) as u32).to_be_bytes()[1..]);
        let encrypted = client.encrypt(&plain, Some(&header), None)?;
        session
            .handle_frame(FrameType::EOpack, &encrypted, &header)
            .await
    }
    #[tokio::test]
    async fn recoverable_keyboard_rejections_keep_authenticated_remote_alive() {
        for (reason, request) in [
            EventKind::KeyboardNoField,
            EventKind::KeyboardMissingData,
            EventKind::KeyboardDataType,
            EventKind::KeyboardInvalidEncoding,
            EventKind::KeyboardStaleSession,
            EventKind::KeyboardFieldChanged,
        ]
        .into_iter()
        .flat_map(|reason| [(reason, false), (reason, true)])
        {
            let signing = SigningKey::from_bytes(&[7; 32]);
            let (mut session, mut peer) = fixture(vec![PairedClient {
                identifier: "phone".into(),
                public_key: signing.verifying_key().to_bytes(),
            }])
            .await;
            let mut client = verify_client(&mut session, &signing, "phone", false)
                .await
                .unwrap();
            peer_frame(&mut peer).await;
            peer_frame(&mut peer).await; // M2/M4 handshake replies.
            let current = KeyboardState {
                session_id: uuid::Uuid::new_v4().to_string(),
                text: String::new(),
            };
            let delegate = Arc::new(TextDelegate {
                field: if reason == EventKind::KeyboardNoField {
                    None
                } else {
                    Some(current.clone())
                },
                accepts: false,
                events: std::sync::Mutex::new(vec![]),
                buttons: std::sync::Mutex::new(vec![]),
            });
            session.delegate = delegate.clone();
            session.keyboard_subscribed = true;
            let bytes = match reason {
                EventKind::KeyboardInvalidEncoding => b"PRIVATE_MALFORMED_TEXT".to_vec(),
                EventKind::KeyboardStaleSession => keyboard::tests::input(
                    &uuid::Uuid::new_v4().to_string(),
                    "PRIVATE_TYPED_TEXT",
                    false,
                ),
                _ => keyboard::tests::input(&current.session_id, "PRIVATE_TYPED_TEXT", false),
            };
            let content = if reason == EventKind::KeyboardMissingData {
                Value::dict(vec![])
            } else if reason == EventKind::KeyboardDataType {
                Value::dict(vec![Value::kv("_tiD", Value::str("PRIVATE"))])
            } else {
                Value::dict(vec![Value::kv("_tiD", Value::Bytes(bytes))])
            };
            authenticated_input(
                &mut session,
                &mut client,
                Value::dict(vec![
                    Value::kv("_i", Value::str("_tiC")),
                    Value::kv("_t", Value::Int(if request { 2 } else { 1 })),
                    Value::kv("_x", Value::Int(99)),
                    Value::kv("_c", content),
                ]),
            )
            .await
            .unwrap();
            if request {
                let (header, encrypted) = peer_frame(&mut peer).await;
                let response = client.decrypt(&encrypted, Some(&header), None).unwrap();
                let (response, _) = opack::unpack(&response).unwrap();
                assert_eq!(
                    response.get("_em").and_then(Value::as_str),
                    Some("Keyboard input unavailable")
                );
                assert_eq!(response.get("_x").and_then(Value::as_int), Some(99));
            }
            let (header, encrypted) = peer_frame(&mut peer).await;
            let response = client.decrypt(&encrypted, Some(&header), None).unwrap();
            let (response, _) = opack::unpack(&response).unwrap();
            assert_eq!(
                response.get("_i").and_then(Value::as_str),
                Some(if reason == EventKind::KeyboardNoField {
                    "_tiStopped"
                } else {
                    "_tiStarted"
                })
            );
            // Invalid notifications may be retried by iOS. Refresh once per
            // field state, not once per retry: otherwise _tiStarted loops.
            authenticated_input(
                &mut session,
                &mut client,
                Value::dict(vec![
                    Value::kv("_i", Value::str("_tiC")),
                    Value::kv("_t", Value::Int(1)),
                    Value::kv("_c", Value::dict(vec![])),
                ]),
            )
            .await
            .unwrap();
            assert!(
                tokio::time::timeout(Duration::from_millis(20), peer_frame(&mut peer))
                    .await
                    .is_err()
            );
            assert!(delegate.events.lock().unwrap().contains(&reason));
            assert!(session.check_authorised().is_ok());
            authenticated_input(
                &mut session,
                &mut client,
                Value::dict(vec![
                    Value::kv("_i", Value::str("_hidC")),
                    Value::kv("_x", Value::Int(100)),
                    Value::kv(
                        "_c",
                        Value::dict(vec![
                            Value::kv("_hidC", Value::Int(7)),
                            Value::kv("_hBtS", Value::Int(2)),
                        ]),
                    ),
                ]),
            )
            .await
            .unwrap();
            assert_eq!(*delegate.buttons.lock().unwrap(), vec!["home"]);
            session.pairings.revoke("phone").unwrap();
            assert!(
                authenticated_input(&mut session, &mut client, Value::dict(vec![]))
                    .await
                    .is_err()
            );
        }
    }
    #[tokio::test]
    async fn high_xid_system_info_keeps_verified_grace_for_delayed_text_and_session_start() {
        let signing = SigningKey::from_bytes(&[7; 32]);
        let (mut session, _peer) = fixture(vec![PairedClient {
            identifier: "phone".into(),
            public_key: signing.verifying_key().to_bytes(),
        }])
        .await;
        let mut client = verify_client(&mut session, &signing, "phone", false)
            .await
            .unwrap();
        let verified_deadline = session.timeout_deadline.lock().await.unwrap();
        let field = KeyboardState {
            session_id: uuid::Uuid::new_v4().to_string(),
            text: String::new(),
        };
        let delegate = Arc::new(TextDelegate {
            field: Some(field),
            accepts: true,
            events: std::sync::Mutex::new(vec![]),
            buttons: std::sync::Mutex::new(vec![]),
        });
        session.delegate = delegate.clone();
        authenticated_input(
            &mut session,
            &mut client,
            Value::dict(vec![
                Value::kv("_i", Value::str("_systemInfo")),
                Value::kv("_x", Value::Int(0x80000001)),
                Value::kv("_c", Value::dict(vec![])),
            ]),
        )
        .await
        .unwrap();
        assert_eq!(
            *session.timeout_deadline.lock().await,
            Some(verified_deadline)
        );
        tokio::time::sleep(Duration::from_millis(1100)).await;
        assert!(verified_deadline > tokio::time::Instant::now());
        assert!(session.check_authorised().is_ok());
        authenticated_input(
            &mut session,
            &mut client,
            Value::dict(vec![
                Value::kv("_i", Value::str("_tiStart")),
                Value::kv("_t", Value::Int(2)),
                Value::kv("_x", Value::Int(2)),
            ]),
        )
        .await
        .unwrap();
        assert!(session.keyboard_subscribed);
        assert!(session.keyboard_last.is_some());
        assert_eq!(
            *session.timeout_deadline.lock().await,
            Some(verified_deadline),
            "text setup alone does not grant an unbounded idle connection"
        );
        authenticated_input(
            &mut session,
            &mut client,
            Value::dict(vec![
                Value::kv("_i", Value::str("_sessionStart")),
                Value::kv("_x", Value::Int(3)),
            ]),
        )
        .await
        .unwrap();
        assert!(session.is_established());
        assert!(session.timeout_deadline.lock().await.is_none());
        let events = delegate.events.lock().unwrap();
        assert!(events.contains(&EventKind::SystemInfoHighTransactionId));
        assert!(events.contains(&EventKind::TextInputStartReceived));
        assert!(events.contains(&EventKind::SessionStarted));
    }
    #[tokio::test]
    async fn repeated_metadata_cannot_extend_verified_idle_budget() {
        let signing = SigningKey::from_bytes(&[7; 32]);
        let (mut session, _peer) = fixture(vec![PairedClient {
            identifier: "phone".into(),
            public_key: signing.verifying_key().to_bytes(),
        }])
        .await;
        let mut client = verify_client(&mut session, &signing, "phone", false)
            .await
            .unwrap();
        let deadline = session.timeout_deadline.lock().await.unwrap();
        for xid in [1, 0x80000001, 2, 0xffffffff] {
            authenticated_input(
                &mut session,
                &mut client,
                Value::dict(vec![
                    Value::kv("_i", Value::str("_systemInfo")),
                    Value::kv("_x", Value::Int(xid)),
                    Value::kv("_c", Value::dict(vec![])),
                ]),
            )
            .await
            .unwrap();
            assert_eq!(*session.timeout_deadline.lock().await, Some(deadline));
        }
        assert!(deadline <= tokio::time::Instant::now() + Duration::from_secs(30));
        assert!(!session.is_established());
    }
}
