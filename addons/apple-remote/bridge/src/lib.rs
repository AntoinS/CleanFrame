//! Authenticated Companion input to the on-TV host or legacy Android Remote v2.
//! No shell, HTTP control server, logging subscriber or arbitrary key strings.
use atv_core::{
    AtvConfig, AtvDelegate, AtvServer, EventKind, KeyboardEdit, KeyboardState, PairedClient,
    PairingStore, TrackpadMode,
};
use chromiacast::android_tv::{
    AndroidTvError, AndroidTvImeState, AndroidTvKeyCode, AndroidTvPairingSession, AndroidTvRemote,
    AndroidTvRemoteClientInfo, AndroidTvRemoteEvent, AndroidTvRemoteIdentity,
};
use jni::{
    objects::{JClass, JObject, JString, JValue},
    sys::jstring,
    JNIEnv,
};
use serde_json::{json, Value};
use std::{
    collections::VecDeque,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{runtime::Runtime, sync::mpsc};

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
type HostInput = Arc<dyn Fn(&str) -> Result<String, String> + Send + Sync>;
type Persist = Arc<dyn Fn(&str) -> Result<(), String> + Send + Sync>;
struct Vault {
    data: Mutex<Value>,
    persist: Persist,
}
impl Vault {
    fn update(&self, key: &str, value: Value) -> Result<(), String> {
        let mut data = self.data.lock().unwrap();
        let mut next = data.clone();
        next[key] = value;
        (self.persist)(&next.to_string())?;
        *data = next;
        Ok(())
    }
    fn read(&self) -> Value {
        self.data.lock().unwrap().clone()
    }
}
struct Shared {
    backend: &'static str,
    android: String,
    error: String,
    apple: String,
    pin: Option<u32>,
    expires: u64,
    keyboard: Option<KeyboardField>,
    enabled: bool,
    events: VecDeque<Value>,
    sequence: u64,
    connected: bool,
    input_epoch: u64,
}
#[derive(Clone, PartialEq)]
struct KeyboardSnapshot {
    generation: String,
    revision: u64,
    text: String,
    selection_start: i32,
    selection_end: i32,
    editor_action: i32,
    google: Option<AndroidTvImeState>,
}
impl From<AndroidTvImeState> for KeyboardSnapshot {
    fn from(value: AndroidTvImeState) -> Self {
        Self {
            generation: format!(
                "{}:{}:{}:{}",
                value.session_id, value.field_generation, value.field_counter, value.label
            ),
            revision: value.revision,
            text: value.text.clone(),
            selection_start: value.selection_start,
            selection_end: value.selection_end,
            editor_action: 0,
            google: Some(value),
        }
    }
}
impl KeyboardSnapshot {
    fn host(value: &Value) -> Option<Self> {
        if value["ready"] != true {
            return None;
        }
        let generation = uuid::Uuid::parse_str(value["generation"].as_str()?)
            .ok()?
            .to_string();
        let text = value["text"].as_str()?;
        let units = text.encode_utf16().count();
        if text.len() > 16384 || units > 8192 || text.contains('\0') {
            return None;
        }
        let start = i32::try_from(value["selectionStart"].as_i64()?).ok()?;
        let end = i32::try_from(value["selectionEnd"].as_i64()?).ok()?;
        if start < 0 || end < 0 || start as usize > units || end as usize > units {
            return None;
        }
        Some(Self {
            generation,
            revision: value["revision"].as_u64()?,
            text: text.into(),
            selection_start: start,
            selection_end: end,
            editor_action: i32::try_from(value["editorAction"].as_i64()?).ok()?,
            google: None,
        })
    }
}
// The acknowledged Android ticket and the latest iPhone desired value are
// distinct. Only a locally committed operation may advance an existing lane.
struct KeyboardField {
    id: String,
    acknowledged: KeyboardSnapshot,
    desired: String,
    queued: u64,
    delivered: u64,
}
impl KeyboardField {
    fn supports_remote_position(&self) -> bool {
        let end = self.acknowledged.text.encode_utf16().count() as i32;
        self.acknowledged.selection_start == end && self.acknowledged.selection_end == end
    }

    fn new(state: KeyboardSnapshot) -> Self {
        Self {
            id: uuid::Uuid::new_v4().to_string(),
            desired: state.text.clone(),
            acknowledged: state,
            queued: 0,
            delivered: 0,
        }
    }
}
// Counter-only telemetry after an accepted local value is an acknowledgement,
// not permission to rebase onto unrelated external text or another selection.
fn same_field_value(a: &KeyboardSnapshot, b: &KeyboardSnapshot) -> bool {
    a.generation == b.generation
        && a.editor_action == b.editor_action
        && a.text == b.text
        && a.selection_start == b.selection_start
        && a.selection_end == b.selection_end
}
fn accepted_echo(field: &KeyboardField, current: &KeyboardSnapshot) -> bool {
    field.delivered > 0
        && current.revision >= field.acknowledged.revision
        && same_field_value(current, &field.acknowledged)
}
struct QueuedEdit {
    edit: KeyboardEdit,
    sequence: u64,
}
impl Shared {
    fn event(&mut self, kind: &str, action: &str) {
        self.sequence += 1;
        self.events
            .push_back(json!({"id":self.sequence,"time":now(),"kind":kind,"action":action}));
        while self.events.len() > 200 {
            self.events.pop_front();
        }
    }
    fn status(&self, devices: Vec<Value>) -> Value {
        json!({"running":self.enabled,"backend":self.backend,"android":{"state":self.android,"error":self.error},"apple":{"state":self.apple,"pin":if self.expires>now(){self.pin.map(|p|format!("{p:04}"))}else{None},"expiresAt":self.expires,"devices":devices},"keyboard":{"ready":self.enabled&&self.keyboard.as_ref().is_some_and(KeyboardField::supports_remote_position)},"events":self.events})
    }
    fn observe_keyboard(&mut self, current: Option<KeyboardSnapshot>) {
        if !self.enabled {
            self.keyboard = None;
            return;
        }
        if self.keyboard.as_ref().map(|f| &f.acknowledged) == current.as_ref() {
            return;
        }
        if let (Some(field), Some(current)) = (self.keyboard.as_mut(), current.as_ref()) {
            if accepted_echo(field, current) {
                field.acknowledged = current.clone();
                return;
            }
        }
        let pending = self
            .keyboard
            .as_ref()
            .is_some_and(|f| f.queued != f.delivered);
        self.keyboard = current.map(KeyboardField::new);
        if pending {
            self.event("rejected", "external_text_change");
        }
    }
    fn reset_keyboard(&mut self, current: Option<KeyboardSnapshot>) {
        self.keyboard = None;
        self.observe_keyboard(current);
    }
    fn text_ticket(
        &mut self,
        queued: &QueuedEdit,
        current: Option<&KeyboardSnapshot>,
    ) -> Option<KeyboardSnapshot> {
        if !self.enabled {
            return None;
        }
        let field = self.keyboard.as_mut()?;
        if let Some(current) = current {
            if accepted_echo(field, current) {
                field.acknowledged = current.clone();
            }
        }
        if queued.edit.session_id != field.id
            || queued.sequence != field.delivered.checked_add(1)?
            || current != Some(&field.acknowledged)
        {
            return None;
        }
        Some(field.acknowledged.clone())
    }
    fn finish_text(
        &mut self,
        queued: &QueuedEdit,
        before: &KeyboardSnapshot,
        current: Option<KeyboardSnapshot>,
        ok: bool,
    ) {
        if !self.enabled {
            return;
        }
        let mut expected = before.clone();
        if !queued.edit.submit {
            expected.text = queued.edit.text.clone();
            expected.revision = expected.revision.wrapping_add(1);
            expected.selection_start = expected.text.encode_utf16().count() as i32;
            expected.selection_end = expected.selection_start;
        }
        let same = self
            .keyboard
            .as_ref()
            .is_some_and(|f| f.id == queued.edit.session_id);
        if ok
            && same
            && current
                .as_ref()
                .is_some_and(|v| v.revision >= expected.revision && same_field_value(v, &expected))
        {
            let field = self.keyboard.as_mut().unwrap();
            field.acknowledged = current.unwrap();
            field.delivered = queued.sequence;
            if field.queued == field.delivered {
                field.desired = field.acknowledged.text.clone();
            }
            self.event("delivered", "text_edit");
        } else {
            self.reset_keyboard(current);
            self.event(
                if ok { "rejected" } else { "failed" },
                "text_edit_state_changed",
            );
        }
    }
}
enum Command {
    PairBegin,
    PairFinish(String),
    ApplePair,
    Unpair(String),
    Button(String, u64),
    Text(QueuedEdit),
}
struct Delegate {
    state: Arc<Mutex<Shared>>,
    tx: mpsc::Sender<Command>,
}
impl AtvDelegate for Delegate {
    fn on_button(&self, name: &str) {
        let mut state = self.state.lock().unwrap();
        if !state.enabled || key(name).is_none() {
            return;
        }
        if !state.connected {
            // Authenticated, allowlisted action received; no backend command.
            state.event("unavailable", name);
            return;
        }
        let _ = self
            .tx
            .try_send(Command::Button(name.into(), state.input_epoch));
    }
    fn on_event(&self, kind: EventKind, _detail: &str) {
        let mut s = self.state.lock().unwrap();
        if !s.enabled {
            return;
        }
        // No caller-provided detail is logged. Structural tokens have private
        // fields and are generated from fixed protocol names by atv-core.
        if let EventKind::KeyboardStructure(shape) = kind {
            s.event("apple_shape", &shape.label());
            return;
        }
        let label = format!("{kind:?}");
        if kind == EventKind::Paired {
            s.pin = None;
            s.expires = 0;
            s.apple = "paired".into();
        }
        if label == "Verified" {
            s.apple = "connected".into();
        }
        if label == "ClientDisconnected" {
            s.apple = "listening".into();
        }
        s.event("apple", &label);
    }
    fn keyboard_state(&self) -> Option<KeyboardState> {
        let state = self.state.lock().unwrap();
        if !state.enabled {
            return None;
        }
        state
            .keyboard
            .as_ref()
            .filter(|field| field.supports_remote_position())
            .map(|field| KeyboardState {
                session_id: field.id.clone(),
                text: field.desired.clone(),
            })
    }
    fn keyboard_edit(&self, edit: KeyboardEdit) -> bool {
        let mut state = self.state.lock().unwrap();
        if !state.enabled
            || edit.text.len() > 16384
            || edit.text.encode_utf16().count() > 8192
            || edit.text.contains('\0')
        {
            return false;
        }
        let Some(field) = state
            .keyboard
            .as_mut()
            .filter(|f| f.id == edit.session_id && f.supports_remote_position())
        else {
            return false;
        };
        // Keyboard-language metadata may carry an unchanged document. It does
        // not require an InputConnection mutation or a new Android revision.
        if !edit.submit && edit.text == field.desired {
            return true;
        }
        let Some(sequence) = field.queued.checked_add(1) else {
            return false;
        };
        let desired = edit.text.clone();
        if self
            .tx
            .try_send(Command::Text(QueuedEdit { edit, sequence }))
            .is_err()
        {
            return false;
        }
        field.queued = sequence;
        field.desired = desired;
        true
    }
}
fn key(name: &str) -> Option<AndroidTvKeyCode> {
    Some(match name {
        "up" => AndroidTvKeyCode::DPAD_UP,
        "down" => AndroidTvKeyCode::DPAD_DOWN,
        "left" => AndroidTvKeyCode::DPAD_LEFT,
        "right" => AndroidTvKeyCode::DPAD_RIGHT,
        "select" => AndroidTvKeyCode::DPAD_CENTER,
        "menu" => AndroidTvKeyCode::BACK,
        "home" => AndroidTvKeyCode::HOME,
        "play_pause" => AndroidTvKeyCode::MEDIA_PLAY_PAUSE,
        "volume_up" => AndroidTvKeyCode::VOLUME_UP,
        "volume_down" => AndroidTvKeyCode::VOLUME_DOWN,
        "mute" => AndroidTvKeyCode::VOLUME_MUTE,
        "sleep" => AndroidTvKeyCode::SLEEP,
        "wake" => AndroidTvKeyCode::WAKEUP,
        "power" => AndroidTvKeyCode::POWER,
        _ => return None,
    })
}
fn decode<const N: usize>(value: &Value) -> Result<[u8; N], String> {
    hex::decode(value.as_str().ok_or("Missing identity")?)
        .map_err(|_| "Invalid identity")?
        .try_into()
        .map_err(|_| "Invalid identity length".into())
}
struct Engine {
    tx: mpsc::Sender<Command>,
    state: Arc<Mutex<Shared>>,
    pairings: Arc<PairingStore>,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Engine {
    fn drop(&mut self) {
        {
            let mut s = self.state.lock().unwrap();
            s.enabled = false;
            s.keyboard = None;
            s.pin = None;
            s.connected = false;
            s.apple = "stopped".into();
            s.android = "stopped".into();
        }
        self.pairings.close_pairing();
        self.task.abort();
    }
}
static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
static RUNTIME: OnceLock<Runtime> = OnceLock::new();
fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("Tokio runtime"))
}

fn start(args: &Value, persist: Persist, host: HostInput) -> Result<Engine, String> {
    let name = args["name"]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 80)
        .unwrap_or("CleanFrame TV")
        .to_string();
    let ip: Ipv4Addr = args["address"]
        .as_str()
        .ok_or("Network unavailable")?
        .parse()
        .map_err(|_| "Network unavailable")?;
    if ip.is_loopback() || ip.is_unspecified() || ip.is_multicast() {
        return Err("Network unavailable".into());
    }
    let mut secrets = args
        .get("secrets")
        .filter(|v| v.is_object())
        .cloned()
        .unwrap_or(json!({}));
    if secrets.get("appleKey").is_none() {
        secrets["appleKey"] = json!(hex::encode(rand::random::<[u8; 32]>()));
        secrets["id"] = json!(uuid::Uuid::new_v4().to_string());
        secrets["clients"] = json!([]);
        persist(&secrets.to_string())?;
    }
    let private_key = decode::<32>(&secrets["appleKey"])?;
    let id = uuid::Uuid::parse_str(secrets["id"].as_str().ok_or("Missing identity")?)
        .map_err(|_| "Invalid identity")?
        .to_string();
    let mut clients = vec![];
    for p in secrets["clients"].as_array().ok_or("Invalid pairings")? {
        clients.push(PairedClient {
            identifier: p["id"].as_str().ok_or("Invalid pairing")?.into(),
            public_key: decode::<32>(&p["key"])?,
        });
    }
    let vault = Arc::new(Vault {
        data: Mutex::new(secrets),
        persist,
    });
    let callback = vault.clone();
    let pairings = Arc::new(
        PairingStore::new(
            clients,
            Arc::new(move |clients| {
                callback.update(
                    "clients",
                    json!(clients
                        .iter()
                        .map(|p| json!({"id":p.identifier,"key":hex::encode(p.public_key)}))
                        .collect::<Vec<_>>()),
                )
            }),
        )
        .map_err(|_| "Invalid pairings")?,
    );
    let host_mode = args["backend"] == "host";
    let state = Arc::new(Mutex::new(Shared {
        backend: if host_mode { "philips" } else { "android" },
        android: "starting".into(),
        error: String::new(),
        apple: "listening".into(),
        pin: None,
        expires: 0,
        keyboard: None,
        enabled: true,
        events: VecDeque::new(),
        sequence: 0,
        connected: false,
        input_epoch: 0,
    }));
    let (tx, rx) = mpsc::channel(32);
    let delegate = Arc::new(Delegate {
        state: state.clone(),
        tx: tx.clone(),
    });
    let config = AtvConfig {
        name,
        ip: Some(ip),
        device_id: Some(format!(
            "02:{}:{}:{}:{}:{}",
            &id[0..2],
            &id[2..4],
            &id[4..6],
            &id[6..8],
            &id[9..11]
        )),
        server_identifier: Some(id),
        private_key: Some(private_key),
        pairings: pairings.clone(),
        trackpad_mode: TrackpadMode::Direction,
        mouse_mode: false,
        ui_port: None,
        ..AtvConfig::default()
    };
    let shared = state.clone();
    let ps = pairings.clone();
    let task = runtime().spawn(async move {
        let result = if host_mode {
            run_host(config, delegate, shared.clone(), ps, rx, host).await
        } else {
            run(config, delegate, vault, shared.clone(), ps, rx).await
        };
        if let Err(_error) = result {
            let mut s = shared.lock().unwrap();
            s.enabled = false;
            s.keyboard = None;
            s.android = "error".into();
            s.error = "Der Fernbedienungsdienst konnte nicht gestartet werden.".into();
            s.apple = "stopped".into();
        }
    });
    Ok(Engine {
        tx,
        state,
        pairings,
        task,
    })
}
fn remote_disconnected(
    events: &mut Option<tokio::sync::broadcast::Receiver<AndroidTvRemoteEvent>>,
) -> bool {
    use tokio::sync::broadcast::error::TryRecvError;
    let Some(events) = events.as_mut() else {
        return false;
    };
    let mut disconnected = false;
    loop {
        match events.try_recv() {
            Ok(AndroidTvRemoteEvent::Disconnected { .. }) => disconnected = true,
            Ok(_) => {}
            Err(TryRecvError::Lagged(_)) => continue,
            Err(TryRecvError::Closed) => return true,
            Err(TryRecvError::Empty) => return disconnected,
        }
    }
}

async fn retire_android(
    remote: &mut Option<AndroidTvRemote>,
    events: &mut Option<tokio::sync::broadcast::Receiver<AndroidTvRemoteEvent>>,
    shared: &Arc<Mutex<Shared>>,
    state: &str,
) {
    // Retire the old receiver before closing its connection. Its final
    // Disconnected event must never overwrite a new pairing transcript.
    *events = None;
    {
        let mut shared = shared.lock().unwrap();
        shared.keyboard = None;
        shared.connected = false;
        shared.android = state.into();
    }
    if let Some(remote) = remote.take() {
        let _ = remote.close().await;
    }
}

// Authentication belongs to the enabled Apple receiver. Backend availability
// gates command delivery, not establishment of a verified iPhone identity.
fn begin_apple_pairing(shared: &Arc<Mutex<Shared>>, pairings: &PairingStore) -> bool {
    let mut state = shared.lock().unwrap();
    if !state.enabled {
        return false;
    }
    let pin = pairings.open_pairing(Duration::from_secs(120));
    state.pin = Some(pin);
    state.expires = now() + 120;
    state.apple = "pairing".into();
    state.event("apple", "pairing_open");
    true
}

fn expire_apple_pairing(shared: &Arc<Mutex<Shared>>, pairings: &PairingStore) {
    let expired = {
        let mut state = shared.lock().unwrap();
        let expired = state.pin.is_some() && state.expires <= now();
        if expired {
            state.pin = None;
            state.expires = 0;
            if state.apple == "pairing" {
                state.apple = "listening".into();
            }
            state.event("apple", "pairing_expired");
        }
        expired
    };
    if expired {
        pairings.close_pairing();
    }
}

fn pairing_failed(shared: &Arc<Mutex<Shared>>, message: &str) {
    let mut shared = shared.lock().unwrap();
    shared.android = "unpaired".into();
    shared.connected = false;
    shared.keyboard = None;
    shared.error = message.into();
}

// Fixed diagnostics only: never expose a PIN, certificate, text payload, or
// a transport error's arbitrary Display string to the setup screen/log.
fn pairing_error(error: &AndroidTvError) -> (&'static str, &'static str) {
    match error {
        AndroidTvError::InvalidPairingCode=>("pairing_code_format","Der Android-Code muss aus sechs Hexadezimalzeichen bestehen."),
        AndroidTvError::PairingCodeMismatch=>("pairing_checksum","Der Code passt nicht zu dieser Kopplung. Bitte den aktuell angezeigten Code verwenden."),
        AndroidTvError::PairingRejected(_)=>("pairing_server_rejected","Der Android-TV-Dienst hat die Kopplung abgelehnt. Bitte neu starten."),
        AndroidTvError::TimedOut("pairing-secret acknowledgement")=>("pairing_ack_timeout","Der Android-TV-Dienst hat den Code nicht rechtzeitig bestätigt. Bitte neu koppeln."),
        AndroidTvError::TimedOut("pairing secret")=>("pairing_write_timeout","Der Code konnte nicht rechtzeitig an Android TV übermittelt werden. Bitte neu koppeln."),
        AndroidTvError::TimedOut(_)=>("pairing_timeout","Der Android-TV-Dienst antwortet nicht rechtzeitig. Bitte neu koppeln."),
        AndroidTvError::Io(error) if error.kind()==std::io::ErrorKind::UnexpectedEof=>("pairing_eof","Der Android-TV-Dienst hat die Kopplungsverbindung geschlossen. Bitte neu koppeln."),
        AndroidTvError::Io(_)|AndroidTvError::ConnectionClosed=>("pairing_io","Die Kopplungsverbindung wurde unterbrochen. Bitte neu koppeln."),
        AndroidTvError::Protocol(message) if message=="expected pairing-secret acknowledgement"=>("pairing_ack_missing","Android TV hat keine gültige Codebestätigung gesendet. Bitte neu koppeln."),
        AndroidTvError::Protocol(_)=>("pairing_protocol","Der Android-TV-Dienst hat eine unerwartete Kopplungsantwort gesendet."),
        AndroidTvError::InvalidIdentity(_)|AndroidTvError::Crypto(_)=>("pairing_identity","Die lokale Android-Kopplungsidentität konnte nicht verwendet werden."),
        AndroidTvError::Connection(_)|AndroidTvError::TcpConnection(_)|AndroidTvError::TlsHandshake(_)=>("pairing_transport","Die geschützte Android-TV-Verbindung konnte nicht hergestellt werden."),
        AndroidTvError::UnsupportedPairingConfiguration=>("pairing_configuration","Der Android-TV-Dienst bietet keine unterstützte Codekopplung an."),
        _=>("pairing_failed","Android-Kopplung fehlgeschlagen. Bitte neu starten."),
    }
}
fn report_pairing_error(shared: &Arc<Mutex<Shared>>, error: &AndroidTvError) {
    let (reason, message) = pairing_error(error);
    pairing_failed(shared, message);
    let mut state = shared.lock().unwrap();
    state.event("failed", reason);
    if let AndroidTvError::PairingRejected(status) = error {
        // An i32 protocol enum is bounded and carries no secret input. Preserve
        // its exact value to separate BAD_SECRET from configuration/cancellation.
        state.event("failed", &format!("pairing_status_{status}"));
        state.error = format!("{message} (Status {status})");
    }
}

// Only fixed labels leave this boundary. io/rustls/protobuf errors can include
// peer-controlled strings; preserve their typed category without their payload.
fn remote_error(error: &AndroidTvError) -> (&'static str, &'static str) {
    match error {
        AndroidTvError::CertificatePinMismatch => (
            "remote_certificate_mismatch",
            "Das Android-TV-Zertifikat stimmt nicht mit der bestätigten Kopplung überein.",
        ),
        AndroidTvError::TcpConnection(_) => (
            "remote_tcp",
            "Der Android-TV-Fernbedienungsdienst ist nicht erreichbar.",
        ),
        AndroidTvError::TlsHandshake(_) => (
            "remote_tls",
            "Die TLS-Verbindung zum Android-TV-Dienst wurde nicht bestätigt.",
        ),
        AndroidTvError::TimedOut("TCP connection") => (
            "remote_tcp_timeout",
            "Der Verbindungsaufbau zum Android-TV-Dienst dauert zu lange.",
        ),
        AndroidTvError::TimedOut("TLS handshake") => (
            "remote_tls_timeout",
            "Der Android-TV-Dienst bestätigt die TLS-Verbindung nicht rechtzeitig.",
        ),
        AndroidTvError::TimedOut(_) => (
            "remote_handshake_timeout",
            "Der Android-TV-Dienst beantwortet die Fernbedienungseinrichtung nicht rechtzeitig.",
        ),
        AndroidTvError::RemoteHandshake { source, .. } => match source.as_ref() {
            AndroidTvError::Protocol(message) if message == "device volume state is invalid" => (
                "remote_volume_invalid",
                "TLS ist verbunden; Android TV meldet einen nicht unterstützten Lautstärkestatus.",
            ),
            AndroidTvError::Protocol(message)
                if message == "device rejected remote configuration" =>
            {
                (
                    "remote_configuration_rejected",
                    "TLS ist verbunden; Android TV lehnt die Fernbedienungskonfiguration ab.",
                )
            }
            AndroidTvError::TimedOut(_) => (
                "remote_handshake_timeout",
                "TLS ist verbunden; die Fernbedienungseinrichtung antwortet nicht rechtzeitig.",
            ),
            AndroidTvError::Io(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => (
                "remote_handshake_eof",
                "TLS ist verbunden; Android TV schließt die Fernbedienungseinrichtung.",
            ),
            AndroidTvError::Io(_) | AndroidTvError::ConnectionClosed => (
                "remote_handshake_io",
                "TLS ist verbunden; die Fernbedienungseinrichtung wurde unterbrochen.",
            ),
            _ => (
                "remote_handshake_protocol",
                "TLS ist verbunden; Android TV sendet eine unerwartete Fernbedienungsantwort.",
            ),
        },
        AndroidTvError::InvalidIdentity(_) | AndroidTvError::Crypto(_) => (
            "remote_identity",
            "Die gespeicherte Android-Kopplungsidentität konnte nicht verwendet werden.",
        ),
        _ => (
            "remote_connection",
            "Die Android-TV-Verbindung konnte nicht eingerichtet werden.",
        ),
    }
}
fn report_remote_error(shared: &Arc<Mutex<Shared>>, error: &AndroidTvError) {
    let (reason, message) = remote_error(error);
    let mut state = shared.lock().unwrap();
    state.android = "disconnected".into();
    state.connected = false;
    state.keyboard = None;
    state.error = message.into();
    if let AndroidTvError::RemoteHandshake { stage, .. } = error {
        // Stage is deliberately allowlisted, even though the library uses static strings.
        state.event(
            "android",
            match *stage {
                "configuration" => "remote_stage_configuration",
                "configuration reply" => "remote_stage_configuration_reply",
                "set-active request" => "remote_stage_active",
                "set-active reply" => "remote_stage_active_reply",
                "remote-start notification" => "remote_stage_start",
                _ => "remote_stage_negotiation",
            },
        );
    }
    for code in error.diagnostic_codes() {
        state.event("failed", &code);
    }
    state.event("failed", reason);
}

// A timed-out blocking JNI operation cannot be cancelled. Retain its handle
// and never start another operation until it has actually returned. Late
// results are discarded, then a fresh state probe re-establishes readiness.
struct HostDispatcher {
    callback: HostInput,
    pending: Option<tokio::task::JoinHandle<Result<String, String>>>,
    timeout: Duration,
}
impl HostDispatcher {
    fn new(callback: HostInput) -> Self {
        Self {
            callback,
            pending: None,
            timeout: Duration::from_secs(4),
        }
    }
    async fn settled(&mut self) -> bool {
        if self
            .pending
            .as_ref()
            .is_some_and(|task| !task.is_finished())
        {
            return false;
        }
        if let Some(task) = self.pending.take() {
            let _ = task.await;
        }
        true
    }
}
impl Drop for HostDispatcher {
    fn drop(&mut self) {
        // Cancels work that has not started; Java's activation ticket guards
        // work already running or posted to its main thread.
        if let Some(task) = &self.pending {
            task.abort();
        }
    }
}
async fn host_call(
    host: &mut HostDispatcher,
    shared: &Arc<Mutex<Shared>>,
    value: Value,
) -> Result<Value, &'static str> {
    if !shared.lock().unwrap().enabled {
        return Err("host_disabled");
    }
    if host.pending.is_some() {
        return Err("host_draining");
    }
    let callback = host.callback.clone();
    let gate = shared.clone();
    let request = value.to_string();
    let epoch = shared.lock().unwrap().input_epoch;
    host.pending = Some(tokio::task::spawn_blocking(move || {
        {
            let state = gate.lock().unwrap();
            if !state.enabled || state.input_epoch != epoch {
                return Err("host_disabled".to_string());
            }
        }
        callback(&request)
    }));
    let response = match tokio::time::timeout(host.timeout, host.pending.as_mut().unwrap()).await {
        Ok(result) => {
            host.pending.take();
            result
                .map_err(|_| "host_unavailable")?
                .map_err(|_| "host_unavailable")?
        }
        Err(_) => {
            // Abort a queued blocking job before it starts. Running JNI is
            // still retained and drained; abort cannot stop that execution.
            host.pending.as_ref().unwrap().abort();
            let mut state = shared.lock().unwrap();
            if state.enabled {
                state.event(
                    "failed",
                    match value["op"].as_str() {
                        Some("state") => "host_timeout_state",
                        Some("key") => "host_timeout_key",
                        Some("edit") => "host_timeout_edit",
                        _ => "host_timeout_operation",
                    },
                );
            }
            return Err("host_timeout");
        }
    };
    if !shared.lock().unwrap().enabled {
        return Err("host_disabled");
    }
    if response.len() > 65536 {
        return Err("host_response");
    }
    let result: Value = serde_json::from_str(&response).map_err(|_| "host_response")?;
    if !result.is_object() {
        return Err("host_response");
    }
    Ok(result)
}
fn observe_host(shared: &Arc<Mutex<Shared>>, value: &Value) {
    let mut s = shared.lock().unwrap();
    if !s.enabled {
        return;
    }
    let connected = value["enabled"] == true && value["connected"] == true;
    if connected != s.connected {
        s.event(
            "backend",
            if connected {
                "connected"
            } else {
                "disconnected"
            },
        );
    }
    s.connected = connected;
    s.android = if connected {
        "connected"
    } else {
        "disconnected"
    }
    .into();
    // IME availability is independent of JointSpace key availability. A known
    // active field remains usable even if Philips has not yet been paired.
    s.observe_keyboard(if value["enabled"] == true {
        KeyboardSnapshot::host(&value["keyboard"])
    } else {
        None
    });
}
fn host_result_error(value: &Value) -> &'static str {
    match value["error"].as_str().unwrap_or("") {
        "disabled" => "host_disabled",
        "unsupported_action" => "host_unsupported_action",
        "backend_unavailable" => "host_backend_unavailable",
        "busy" => "host_busy",
        "timeout" => "host_timeout",
        "invalid_request" | "invalid_text" => "host_invalid_request",
        "stale" => "host_stale_field",
        "field_unavailable" | "unavailable" => "host_field_unavailable",
        _ => "host_operation_failed",
    }
}
fn host_error(shared: &Arc<Mutex<Shared>>, code: &'static str) {
    let mut s = shared.lock().unwrap();
    if !s.enabled {
        return;
    }
    s.connected = false;
    s.keyboard = None;
    s.input_epoch = s.input_epoch.wrapping_add(1);
    s.android = "disconnected".into();
    s.event("failed", code);
}
fn reject_stale(
    shared: &Arc<Mutex<Shared>>,
    queued: &QueuedEdit,
    current: Option<KeyboardSnapshot>,
) {
    let mut s = shared.lock().unwrap();
    if s.keyboard
        .as_ref()
        .is_some_and(|f| f.id == queued.edit.session_id)
    {
        s.reset_keyboard(current);
    }
    s.event("rejected", "stale_text_field");
}
async fn host_command(
    host: &mut HostDispatcher,
    shared: &Arc<Mutex<Shared>>,
    command: Command,
) -> Result<(), &'static str> {
    if !shared.lock().unwrap().enabled {
        return Ok(());
    }
    match command {
        Command::Button(action, epoch) => {
            {
                let state = shared.lock().unwrap();
                if key(&action).is_none() || !state.connected || epoch != state.input_epoch {
                    return Ok(());
                }
            }
            let result = host_call(host, shared, json!({"op":"key","action":action})).await?;
            let mut s = shared.lock().unwrap();
            if s.enabled {
                if result["ok"] != true {
                    s.event("failed", host_result_error(&result));
                }
                s.event(
                    if result["ok"] == true {
                        "delivered"
                    } else {
                        "failed"
                    },
                    &action,
                );
            }
        }
        Command::Text(queued) => {
            let status = host_call(host, shared, json!({"op":"state"})).await?;
            observe_host(shared, &status);
            let current = if status["enabled"] == true {
                KeyboardSnapshot::host(&status["keyboard"])
            } else {
                None
            };
            let ticket = shared
                .lock()
                .unwrap()
                .text_ticket(&queued, current.as_ref());
            if let Some(ticket) = ticket {
                let end = if queued.edit.submit {
                    ticket.selection_end
                } else {
                    queued.edit.text.encode_utf16().count() as i32
                };
                let start = if queued.edit.submit {
                    ticket.selection_start
                } else {
                    end
                };
                let result = host_call(host, shared, json!({"op":"edit", "generation":ticket.generation,
                    "revision":ticket.revision,"text":queued.edit.text,"selectionStart":start,"selectionEnd":end,"submit":queued.edit.submit})).await?;
                if result["ok"] != true {
                    shared
                        .lock()
                        .unwrap()
                        .event("failed", host_result_error(&result));
                }
                shared.lock().unwrap().finish_text(
                    &queued,
                    &ticket,
                    KeyboardSnapshot::host(&result["keyboard"]),
                    result["ok"] == true,
                );
            } else {
                reject_stale(shared, &queued, current);
            }
        }
        _ => {}
    }
    Ok(())
}
async fn run_host(
    config: AtvConfig,
    delegate: Arc<Delegate>,
    shared: Arc<Mutex<Shared>>,
    pairings: Arc<PairingStore>,
    mut rx: mpsc::Receiver<Command>,
    host: HostInput,
) -> Result<(), String> {
    let server = AtvServer::start(config, delegate)
        .await
        .map_err(|_| "Apple discovery failed")?;
    let mut tick = tokio::time::interval(Duration::from_millis(150));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut host = HostDispatcher::new(host);
    let mut faulted = false;
    let mut retry_at = tokio::time::Instant::now();
    loop {
        tokio::select! {
            _ = tick.tick() => {
                if !shared.lock().unwrap().enabled { break; }
                if !faulted || (tokio::time::Instant::now() >= retry_at && host.settled().await) {
                    match host_call(&mut host, &shared, json!({"op":"state"})).await {
                        Ok(status) => {
                            observe_host(&shared, &status);
                            if faulted { let mut state=shared.lock().unwrap(); if state.enabled {state.event("backend","host_recovered");} }
                            faulted=false;
                        }
                        Err(code) => { host_error(&shared, code); faulted = true; retry_at=tokio::time::Instant::now()+Duration::from_secs(1); }
                    }
                }
                expire_apple_pairing(&shared, &pairings);
            }
            command = rx.recv() => {
                if !shared.lock().unwrap().enabled { break; }
                match command {
                    None => break,
                    Some(Command::ApplePair) => { begin_apple_pairing(&shared, &pairings); }
                    Some(Command::Unpair(id)) => {
                        let result = pairings.revoke(&id);
                        shared.lock().unwrap().event(if result.is_ok() {"apple"} else {"failed"}, "pairing_removed");
                    }
                    Some(Command::PairBegin | Command::PairFinish(_)) => {
                        shared.lock().unwrap().event("rejected", "backend_pairing_unsupported");
                    }
                    Some(command) if !faulted => {
                        if let Err(code) = host_command(&mut host, &shared, command).await {
                            host_error(&shared, code); faulted = true; retry_at=tokio::time::Instant::now()+Duration::from_secs(1);
                        }
                    }
                    Some(_) => {}
                }
            }
        }
    }
    pairings.close_pairing();
    server.stop().await;
    Ok(())
}

async fn run(
    config: AtvConfig,
    delegate: Arc<Delegate>,
    vault: Arc<Vault>,
    shared: Arc<Mutex<Shared>>,
    pairings: Arc<PairingStore>,
    mut rx: mpsc::Receiver<Command>,
) -> Result<(), String> {
    let initial = vault.read();
    let identity = if let (Some(cert), Some(key)) = (
        initial["android"]["cert"].as_str(),
        initial["android"]["key"].as_str(),
    ) {
        AndroidTvRemoteIdentity::from_der(
            hex::decode(cert).map_err(|_| "Invalid certificate")?,
            hex::decode(key).map_err(|_| "Invalid key")?,
        )
        .map_err(|_| "Invalid Android identity")?
    } else {
        let value =
            tokio::task::spawn_blocking(|| AndroidTvRemoteIdentity::generate("CleanFrame Remote"))
                .await
                .map_err(|_| "Identity generation failed")?
                .map_err(|_| "Identity generation failed")?;
        vault.update("android",json!({"cert":hex::encode(value.certificate_der()),"key":hex::encode(value.private_key_pkcs8_der())}))?;
        value
    };
    let mut server = Some(
        AtvServer::start(config, delegate)
            .await
            .map_err(|_| "Apple discovery failed")?,
    );
    let remote_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 6466);
    let pair_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 6467);
    let info = AndroidTvRemoteClientInfo::new(
        "CleanFrame Remote",
        "CleanFrame",
        "com.cleanframe.addon.appleremote",
        "0.1.0",
    )
    .map_err(|_| "Invalid client")?;
    let mut remote: Option<AndroidTvRemote> = None;
    let mut pending: Option<(AndroidTvPairingSession, u64)> = None;
    let mut retry = 0u64;
    let mut remote_events: Option<tokio::sync::broadcast::Receiver<AndroidTvRemoteEvent>> = None;
    let mut tick = tokio::time::interval(Duration::from_millis(200));
    loop {
        tokio::select! {
         _=tick.tick()=>{
          let secrets=vault.read();
          if remote.as_ref().is_some_and(|r| !r.is_connected()) || remote_disconnected(&mut remote_events) {
           retire_android(&mut remote,&mut remote_events,&shared,"disconnected").await;
           shared.lock().unwrap().event("android","disconnected");retry=now()+2;
          }
          if remote.is_none()&&pending.is_none()&&now()>=retry{
           if let Ok(pin)=decode::<32>(&secrets["androidPin"]){
            match AndroidTvRemote::connect_address(remote_addr,&identity,info.clone(),pin).await{
             Ok(value)=>{remote_events=Some(value.subscribe());remote=Some(value);let mut s=shared.lock().unwrap();s.android="connected".into();s.error.clear();s.connected=true;s.event("android","connected");}
             Err(error)=>{report_remote_error(&shared,&error);retry=now()+5;}
            }
           }else{shared.lock().unwrap().android="unpaired".into();}
          }
          if pending.as_ref().is_some_and(|(_,expiry)|now()>=*expiry){pending=None;pairing_failed(&shared,"Der Android-Kopplungscode ist abgelaufen.");}
          if let Some(r)=remote.as_ref(){shared.lock().unwrap().observe_keyboard(r.ime_state().map(Into::into));}
          expire_apple_pairing(&shared, &pairings);
         },
         command=rx.recv()=>match command{
          _ if !shared.lock().unwrap().enabled=>break,
          None=>break,
          Some(Command::PairBegin)=>{
           // Drop the preceding PIN transcript before opening another one.
           pending=None;
           retire_android(&mut remote,&mut remote_events,&shared,"pairing").await;
           shared.lock().unwrap().error.clear();
           match AndroidTvPairingSession::begin_address(pair_addr,&identity,"CleanFrame Remote").await{
            Ok(p)=>{pending=Some((p,now()+120));shared.lock().unwrap().event("android","pairing");},
            Err(error)=>report_pairing_error(&shared,&error),
           }
          },
          Some(Command::PairFinish(code))=>{
           shared.lock().unwrap().event("android","pairing_finish");
           if let Some((p,expiry))=pending.take(){
            if now()>=expiry{pairing_failed(&shared,"Kopplungscode abgelaufen. Kopplung erneut starten.");continue;}
            match p.finish(&code).await {
             Ok(pin)=>{
              if vault.update("androidPin",json!(hex::encode(pin))).is_err(){pairing_failed(&shared,"Kopplung konnte nicht gespeichert werden.");continue;}
              retry=0;let mut s=shared.lock().unwrap();s.android="connecting".into();s.error.clear();
             },
             Err(error)=>report_pairing_error(&shared,&error),
            }
           }else{shared.lock().unwrap().error="Keine Android-Kopplung geöffnet. Kopplung zuerst starten.".into();}
          },
          Some(Command::ApplePair)=>{begin_apple_pairing(&shared,&pairings);},
          Some(Command::Unpair(id))=>{let result=pairings.revoke(&id);let mut state=shared.lock().unwrap();if result.is_ok(){state.event("apple","pairing_removed");}else{state.error="Kopplung konnte nicht entfernt werden.".into();state.event("failed","pairing_remove");}},
          Some(Command::Button(action,_))=>{if let (Some(r),Some(k))=(remote.as_ref().filter(|r|r.is_connected()),key(&action)){
           let ok=r.press_key(k).await.is_ok();
           {let mut s=shared.lock().unwrap();s.event(if ok{"delivered"}else{"failed"},&action);if !ok{s.keyboard=None;s.android="disconnected".into();}}
           if !ok{retire_android(&mut remote,&mut remote_events,&shared,"disconnected").await;retry=now()+1;}
          }},
          Some(Command::Text(queued))=>{
           if let Some(r)=remote.as_ref(){
            let current=r.ime_state().map(Into::into);let ticket=shared.lock().unwrap().text_ticket(&queued,current.as_ref());
            if let Some(ticket)=ticket {
             let result=if queued.edit.submit{r.submit_text_field(ticket.google.as_ref().unwrap()).await}else{r.replace_text(ticket.google.as_ref().unwrap(),&queued.edit.text).await};
             shared.lock().unwrap().finish_text(&queued,&ticket,r.ime_state().map(Into::into),result.is_ok());
            }else{
             let mut s=shared.lock().unwrap();
             // Old messages cannot invalidate a newer field's accepted queue.
             if s.keyboard.as_ref().is_some_and(|f|f.id==queued.edit.session_id){s.reset_keyboard(current);}
             s.event("rejected","stale_text_field");
            }
           }else{shared.lock().unwrap().reset_keyboard(None);}
          }
         }
        }
    }
    pairings.close_pairing();
    if let Some(s) = server.take() {
        s.stop().await;
    }
    if let Some(r) = remote {
        let _ = r.close().await;
    }
    Ok(())
}
fn request(value: Value, persist: Persist, host: HostInput) -> Value {
    let op = value["op"].as_str().unwrap_or("");
    let mut engine = ENGINE.lock().unwrap();
    if op == "start" && engine.is_none() {
        match start(&value, persist, host) {
            Ok(e) => *engine = Some(e),
            Err(_) => {
                return json!({"running":false,"error":"Identität oder Netzwerk nicht verfügbar."})
            }
        }
    }
    if op == "stop" {
        drop(engine.take());
        return json!({"running":false});
    }
    let Some(e) = engine.as_ref() else {
        return json!({"running":false,"android":{"state":"stopped"},"apple":{"state":"stopped"}});
    };
    let command = match op {
        "start" | "status" => None,
        "androidPairBegin" => Some(Command::PairBegin),
        "androidPairFinish" => {
            let c = value["code"].as_str().unwrap_or("");
            if c.len() != 6 || !c.chars().all(|x| x.is_ascii_hexdigit()) {
                return json!({"error":"Bitte den sechsstelligen Android-Code eingeben."});
            }
            Some(Command::PairFinish(c.into()))
        }
        "applePairBegin" => Some(Command::ApplePair),
        "unpair" => {
            let id = value["id"].as_str().unwrap_or("");
            if id.is_empty() || id.len() > 128 {
                return json!({"error":"Unbekanntes Gerät."});
            }
            Some(Command::Unpair(id.into()))
        }
        _ => return json!({"error":"Unbekannte Aktion."}),
    };
    if let Some(c) = command {
        if e.tx.try_send(c).is_err() {
            return json!({"error":"Dienst beschäftigt. Bitte erneut versuchen."});
        }
    }
    let devices = e
        .pairings
        .list()
        .iter()
        .map(|p| json!({"id":p.identifier,"name":"iPhone"}))
        .collect();
    let result = e.state.lock().unwrap().status(devices);
    result
}
#[no_mangle]
pub extern "system" fn Java_com_cleanframe_addon_appleremote_RemoteNative_request(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let result = (|| -> Result<Value, String> {
        let raw: String = env.get_string(&input).map_err(|_| "Invalid input")?.into();
        if raw.len() > 262144 {
            return Err("Request too large".into());
        }
        let value: Value = serde_json::from_str(&raw).map_err(|_| "Invalid JSON")?;
        let vm = Arc::new(env.get_java_vm().map_err(|_| "JNI unavailable")?);
        let class = env
            .find_class("com/cleanframe/addon/appleremote/RemoteNative")
            .map_err(|_| "Storage unavailable")?;
        let class = env
            .new_global_ref(class)
            .map_err(|_| "Storage unavailable")?;
        let host_vm = vm.clone();
        let host_class = class.clone();
        let host: HostInput = Arc::new(move |text| {
            let mut env = host_vm.attach_current_thread().map_err(|_| "host_jni")?;
            env.push_local_frame(16).map_err(|_| "host_jni")?;
            let result = (|| -> Result<String, String> {
                let input = env.new_string(text).map_err(|_| "host_jni")?;
                let value = env
                    .call_static_method(
                        <&JClass>::from(host_class.as_obj()),
                        "hostInput",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        &[JValue::Object(&JObject::from(input))],
                    )
                    .map_err(|_| "host_jni")?
                    .l()
                    .map_err(|_| "host_jni")?;
                if value.is_null() {
                    return Err("host_response".into());
                }
                let text: String = env
                    .get_string(&JString::from(value))
                    .map_err(|_| "host_response")?
                    .into();
                if text.len() > 65536 {
                    return Err("host_response".into());
                }
                Ok(text)
            })();
            // Java exceptions stay private and must not poison this attached thread.
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
            unsafe {
                let _ = env.pop_local_frame(&JObject::null());
            }
            result
        });
        let persist: Persist = Arc::new(move |text| {
            let mut env = vm
                .attach_current_thread()
                .map_err(|_| "Storage unavailable")?;
            let input = env.new_string(text).map_err(|_| "Storage unavailable")?;
            let result = env
                .call_static_method(
                    <&JClass>::from(class.as_obj()),
                    "persistSecrets",
                    "(Ljava/lang/String;)Z",
                    &[JValue::Object(&JObject::from(input))],
                )
                .map_err(|_| "Storage unavailable")?;
            if result.z().unwrap_or(false) {
                Ok(())
            } else {
                Err("Storage failed".into())
            }
        });
        Ok(request(value, persist, host))
    })();
    let output = result
        .unwrap_or_else(|_| json!({"error":"Ungültige Anfrage oder Speicherfehler."}))
        .to_string();
    env.new_string(output)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn delegate_event_details_cannot_expose_caller_text() {
        let shared = state();
        let (tx, _rx) = mpsc::channel(1);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        for kind in [
            EventKind::KeyboardUnsupportedOperation,
            EventKind::ControlMessage,
            EventKind::Verified,
        ] {
            delegate.on_event(kind, "SECRET_TYPED_TEXT_AND_UUID");
        }
        let log = serde_json::to_string(&shared.lock().unwrap().events).unwrap();
        assert!(!log.contains("SECRET"));
        assert!(!log.contains("TYPED"));
        assert!(log.contains("KeyboardUnsupportedOperation"));
    }
    #[test]
    fn closed_action_set() {
        assert!(key("up").is_some());
        assert!(key("0;reboot").is_none());
        assert!(key("siri").is_none());
    }
    #[test]
    fn vault_does_not_apply_failed_storage() {
        let v = Vault {
            data: Mutex::new(json!({"a":1})),
            persist: Arc::new(|_| Err("disk".into())),
        };
        assert!(v.update("a", json!(2)).is_err());
        assert_eq!(v.read()["a"], 1);
    }
    fn field() -> KeyboardSnapshot {
        AndroidTvImeState {
            session_id: 1,
            revision: 2,
            field_generation: 3,
            ime_counter: 4,
            field_counter: 5,
            text: String::new(),
            selection_start: 0,
            selection_end: 0,
            label: "Search".into(),
        }
        .into()
    }
    fn state() -> Arc<Mutex<Shared>> {
        Arc::new(Mutex::new(Shared {
            backend: "android",
            android: "connected".into(),
            error: String::new(),
            apple: "connected".into(),
            pin: None,
            expires: 0,
            keyboard: Some(KeyboardField::new(field())),
            enabled: true,
            events: VecDeque::new(),
            sequence: 0,
            connected: true,
            input_epoch: 0,
        }))
    }
    fn delivered(before: &KeyboardSnapshot, text: &str) -> KeyboardSnapshot {
        let mut state = before.clone();
        state.revision += 1;
        state.text = text.into();
        state.selection_start = text.encode_utf16().count() as i32;
        state.selection_end = state.selection_start;
        state
    }
    #[tokio::test]
    async fn old_disconnect_cannot_overwrite_new_pairing_state() {
        let shared = state();
        let (sender, receiver) = tokio::sync::broadcast::channel(4);
        let mut events = Some(receiver);
        sender
            .send(AndroidTvRemoteEvent::Disconnected { error: None })
            .unwrap();
        let mut remote = None;
        retire_android(&mut remote, &mut events, &shared, "pairing").await;
        assert!(!remote_disconnected(&mut events));
        assert!(sender
            .send(AndroidTvRemoteEvent::Disconnected { error: None })
            .is_err());
        let state = shared.lock().unwrap();
        assert_eq!(state.android, "pairing");
        assert!(!state.connected);
        assert!(state.keyboard.is_none());
    }
    #[test]
    fn lagged_notifications_do_not_hide_disconnection() {
        let (sender, receiver) = tokio::sync::broadcast::channel(1);
        sender
            .send(AndroidTvRemoteEvent::PowerChanged { powered_on: true })
            .unwrap();
        sender
            .send(AndroidTvRemoteEvent::Disconnected { error: None })
            .unwrap();
        assert!(remote_disconnected(&mut Some(receiver)));
    }
    #[test]
    fn pairing_diagnostics_are_distinct_and_never_echo_input() {
        let cases = [
            (AndroidTvError::PairingCodeMismatch, "pairing_checksum"),
            (
                AndroidTvError::PairingRejected(400),
                "pairing_server_rejected",
            ),
            (
                AndroidTvError::TimedOut("pairing-secret acknowledgement"),
                "pairing_ack_timeout",
            ),
            (
                AndroidTvError::Io(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "PRIVATE_INPUT",
                )),
                "pairing_eof",
            ),
            (
                AndroidTvError::Protocol("PRIVATE_INPUT".into()),
                "pairing_protocol",
            ),
        ];
        for (error, expected) in cases {
            let shared = state();
            report_pairing_error(&shared, &error);
            let state = shared.lock().unwrap();
            assert!(state.events.iter().any(|event| event["action"] == expected));
            if let AndroidTvError::PairingRejected(status) = error {
                assert_eq!(
                    state.events.back().unwrap()["action"],
                    format!("pairing_status_{status}")
                );
            }
            assert!(!state.status(vec![]).to_string().contains("PRIVATE_INPUT"));
            assert!(state.error.len() < 200);
        }
    }

    #[test]
    fn connection_diagnostics_distinguish_pin_tls_and_protocol_without_payloads() {
        let cases = vec![
            (
                AndroidTvError::CertificatePinMismatch,
                "remote_certificate_mismatch",
            ),
            (
                AndroidTvError::TlsHandshake(std::io::Error::other("PRIVATE_INPUT")),
                "remote_tls",
            ),
            (
                AndroidTvError::TcpConnection(std::io::Error::other("PRIVATE_INPUT")),
                "remote_tcp",
            ),
            (
                AndroidTvError::RemoteHandshake {
                    stage: "configuration",
                    source: Box::new(AndroidTvError::Io(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        "PRIVATE_INPUT",
                    ))),
                },
                "remote_handshake_eof",
            ),
            (
                AndroidTvError::RemoteHandshake {
                    stage: "remote-start notification",
                    source: Box::new(AndroidTvError::TimedOut("PRIVATE_INPUT")),
                },
                "remote_handshake_timeout",
            ),
            (
                AndroidTvError::RemoteHandshake {
                    stage: "PRIVATE_INPUT",
                    source: Box::new(AndroidTvError::Protocol("PRIVATE_INPUT".into())),
                },
                "remote_handshake_protocol",
            ),
        ];
        for (error, label) in cases {
            let shared = state();
            report_remote_error(&shared, &error);
            let state = shared.lock().unwrap();
            assert_eq!(state.events.back().unwrap()["action"], label);
            assert!(!state.status(vec![]).to_string().contains("PRIVATE_INPUT"));
            assert!(!state.connected);
            assert!(state.keyboard.is_none());
            if let AndroidTvError::RemoteHandshake {
                stage: "configuration",
                ..
            } = error
            {
                assert!(state
                    .events
                    .iter()
                    .any(|event| event["action"] == "remote_stage_configuration"));
            }
        }
    }

    #[test]
    fn pairing_failure_clears_connection_and_field_state() {
        let shared = state();
        pairing_failed(&shared, "Wrong code");
        let state = shared.lock().unwrap();
        assert_eq!(state.android, "unpaired");
        assert_eq!(state.error, "Wrong code");
        assert!(!state.connected);
        assert!(state.keyboard.is_none());
    }

    #[test]
    fn burst_text_keeps_every_character_and_advances_only_local_tickets() {
        let shared = state();
        let (tx, mut rx) = mpsc::channel(32);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        let id = delegate.keyboard_state().unwrap().session_id;
        for append in ["a", "ä", "🎵"] {
            let previous = delegate.keyboard_state().unwrap();
            assert!(delegate.keyboard_edit(KeyboardEdit {
                session_id: id.clone(),
                text: previous.text + append,
                submit: false
            }));
        }
        assert_eq!(delegate.keyboard_state().unwrap().text, "aä🎵");
        let mut android = field();
        for expected in ["a", "aä", "aä🎵"] {
            if expected != "a" {
                android.revision += 1;
                android.google.as_mut().unwrap().ime_counter += 1;
            }
            let Command::Text(queued) = rx.try_recv().unwrap() else {
                panic!("text expected")
            };
            let ticket = shared
                .lock()
                .unwrap()
                .text_ticket(&queued, Some(&android))
                .unwrap();
            assert_eq!(queued.edit.text, expected);
            android = delivered(&ticket, expected);
            shared
                .lock()
                .unwrap()
                .finish_text(&queued, &ticket, Some(android.clone()), true);
            assert_eq!(delegate.keyboard_state().unwrap().text, "aä🎵");
            shared
                .lock()
                .unwrap()
                .observe_keyboard(Some(android.clone()));
            assert_eq!(delegate.keyboard_state().unwrap().session_id, id);
        }
        assert_eq!(
            shared.lock().unwrap().keyboard.as_ref().unwrap().delivered,
            3
        );
    }
    #[test]
    fn external_change_retires_old_queue_and_field_identity() {
        let shared = state();
        let (tx, mut rx) = mpsc::channel(32);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        let old = delegate.keyboard_state().unwrap();
        assert!(delegate.keyboard_edit(KeyboardEdit {
            session_id: old.session_id.clone(),
            text: "old pending".into(),
            submit: false
        }));
        let Command::Text(queued) = rx.try_recv().unwrap() else {
            panic!("text expected")
        };
        let mut external = field();
        external.revision += 1;
        external.text = "TV input".into();
        external.selection_start = external.text.encode_utf16().count() as i32;
        external.selection_end = external.selection_start;
        assert!(shared
            .lock()
            .unwrap()
            .text_ticket(&queued, Some(&external))
            .is_none());
        shared.lock().unwrap().observe_keyboard(Some(external));
        let next = delegate.keyboard_state().unwrap();
        assert_ne!(next.session_id, old.session_id);
        assert_eq!(next.text, "TV input");
        assert!(!delegate.keyboard_edit(KeyboardEdit {
            session_id: old.session_id,
            text: "stale".into(),
            submit: false
        }));
    }
    #[test]
    fn failed_send_and_full_queue_do_not_keep_uncommitted_text() {
        let shared = state();
        let (tx, mut rx) = mpsc::channel(1);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        let id = delegate.keyboard_state().unwrap().session_id;
        assert!(delegate.keyboard_edit(KeyboardEdit {
            session_id: id.clone(),
            text: "a".into(),
            submit: false
        }));
        assert!(!delegate.keyboard_edit(KeyboardEdit {
            session_id: id,
            text: "ab".into(),
            submit: false
        }));
        assert_eq!(delegate.keyboard_state().unwrap().text, "a");
        let Command::Text(queued) = rx.try_recv().unwrap() else {
            panic!("text expected")
        };
        shared
            .lock()
            .unwrap()
            .finish_text(&queued, &field(), Some(field()), false);
        assert_eq!(delegate.keyboard_state().unwrap().text, "");
    }
    #[tokio::test]
    async fn engine_drop_immediately_disables_callbacks_even_with_full_queue() {
        let shared = state();
        let (tx, mut rx) = mpsc::channel(1);
        let delegate = Delegate {
            state: shared.clone(),
            tx: tx.clone(),
        };
        delegate.on_button("up");
        let task = tokio::spawn(std::future::pending::<()>());
        let engine = Engine {
            tx,
            state: shared.clone(),
            pairings: Arc::new(PairingStore::new(vec![], Arc::new(|_| Ok(()))).unwrap()),
            task,
        };
        drop(engine);
        assert!(!shared.lock().unwrap().enabled);
        assert!(delegate.keyboard_state().is_none());
        let _ = rx.try_recv();
        delegate.on_button("down");
        assert!(rx.try_recv().is_err());
    }
    #[test]
    fn apple_pairing_is_independent_but_backend_down_never_queues_keys() {
        let shared = state();
        {
            let mut state = shared.lock().unwrap();
            state.connected = false;
            state.android = "disconnected".into();
            state.keyboard = None;
        }
        let pairings = PairingStore::new(vec![], Arc::new(|_| Ok(()))).unwrap();
        let before = now();
        assert!(begin_apple_pairing(&shared, &pairings));
        {
            let state = shared.lock().unwrap();
            assert_eq!(state.apple, "pairing");
            assert!(state.pin.is_some());
            assert!(state.expires >= before + 120 && state.expires <= now() + 120);
            assert!(!state.connected);
        }
        let (tx, mut rx) = mpsc::channel(1);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        delegate.on_button("home");
        assert!(rx.try_recv().is_err());
        {
            let state = shared.lock().unwrap();
            assert_eq!(state.events.back().unwrap()["kind"], "unavailable");
            assert_eq!(state.events.back().unwrap()["action"], "home");
        }
        shared.lock().unwrap().connected = true;
        delegate.on_button("home");
        assert!(matches!(rx.try_recv(),Ok(Command::Button(name,_)) if name=="home"));
        pairings.close_pairing();
        {
            let mut state = shared.lock().unwrap();
            state.enabled = false;
            state.pin = None;
        }
        assert!(!begin_apple_pairing(&shared, &pairings));
        assert!(shared.lock().unwrap().pin.is_none());
        delegate.on_button("home");
        assert!(rx.try_recv().is_err());
    }
    fn host_snapshot() -> Value {
        json!({"generation":"a77938ae-286b-47ca-9bd4-5c7d2f4e40cb", "revision":1,
            "text":"", "selectionStart":0,"selectionEnd":0,"editorAction":3,"ready":true,"reason":"ready"})
    }
    fn mock_host(snapshot: Arc<Mutex<Value>>, calls: Arc<Mutex<Vec<Value>>>) -> HostInput {
        Arc::new(move |raw| {
            let request: Value = serde_json::from_str(raw).unwrap();
            calls.lock().unwrap().push(request.clone());
            let mut keyboard = snapshot.lock().unwrap();
            Ok(match request["op"].as_str().unwrap() {
                "state" => json!({"enabled":true,"connected":true,"keyboard":*keyboard}),
                "edit" => {
                    assert_eq!(request["generation"], keyboard["generation"]);
                    assert_eq!(request["revision"], keyboard["revision"]);
                    if request["submit"] != true {
                        keyboard["text"] = request["text"].clone();
                        keyboard["selectionStart"] = request["selectionStart"].clone();
                        keyboard["selectionEnd"] = request["selectionEnd"].clone();
                    }
                    keyboard["revision"] = json!(keyboard["revision"].as_u64().unwrap() + 1);
                    json!({"ok":true,"keyboard":*keyboard})
                }
                "key" => json!({"ok":true}),
                _ => panic!("unexpected host operation"),
            }
            .to_string())
        })
    }
    #[tokio::test]
    async fn host_burst_unicode_edits_are_ordered_and_keep_optimistic_text() {
        let shared = state();
        let snapshot = Arc::new(Mutex::new(host_snapshot()));
        let calls = Arc::new(Mutex::new(vec![]));
        let mut host = HostDispatcher::new(mock_host(snapshot.clone(), calls.clone()));
        observe_host(
            &shared,
            &json!({"enabled":true,"connected":true,"keyboard":*snapshot.lock().unwrap()}),
        );
        let (tx, mut rx) = mpsc::channel(32);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        let id = delegate.keyboard_state().unwrap().session_id;
        for part in ["A", "ä", "🎵"] {
            let text = delegate.keyboard_state().unwrap().text + part;
            assert!(delegate.keyboard_edit(KeyboardEdit {
                session_id: id.clone(),
                text,
                submit: false
            }));
        }
        for expected in ["A", "Aä", "Aä🎵"] {
            host_command(&mut host, &shared, rx.try_recv().unwrap())
                .await
                .unwrap();
            assert_eq!(snapshot.lock().unwrap()["text"], expected);
            assert_eq!(delegate.keyboard_state().unwrap().text, "Aä🎵");
            assert_eq!(delegate.keyboard_state().unwrap().session_id, id);
        }
        assert_eq!(snapshot.lock().unwrap()["selectionEnd"], 4);
        assert_eq!(
            calls
                .lock()
                .unwrap()
                .iter()
                .filter(|v| v["op"] == "edit")
                .count(),
            3
        );
        assert!(!shared
            .lock()
            .unwrap()
            .status(vec![])
            .to_string()
            .contains("Aä🎵"));
    }
    #[tokio::test]
    async fn host_external_selection_and_generation_changes_reject_pending_text() {
        for change_generation in [false, true] {
            let shared = state();
            let mut initial = host_snapshot();
            initial["text"] = json!("ab");
            initial["selectionStart"] = json!(2);
            initial["selectionEnd"] = json!(2);
            let snapshot = Arc::new(Mutex::new(initial));
            let calls = Arc::new(Mutex::new(vec![]));
            let mut host = HostDispatcher::new(mock_host(snapshot.clone(), calls.clone()));
            observe_host(
                &shared,
                &json!({"enabled":true,"connected":true,"keyboard":*snapshot.lock().unwrap()}),
            );
            let (tx, mut rx) = mpsc::channel(32);
            let delegate = Delegate {
                state: shared.clone(),
                tx,
            };
            let id = delegate.keyboard_state().unwrap().session_id;
            assert!(delegate.keyboard_edit(KeyboardEdit {
                session_id: id.clone(),
                text: "abc".into(),
                submit: false
            }));
            {
                let mut value = snapshot.lock().unwrap();
                value["revision"] = json!(2);
                if change_generation {
                    value["generation"] = json!(uuid::Uuid::new_v4().to_string());
                } else {
                    value["selectionStart"] = json!(0);
                    value["selectionEnd"] = json!(0);
                }
            }
            host_command(&mut host, &shared, rx.try_recv().unwrap())
                .await
                .unwrap();
            assert!(!calls.lock().unwrap().iter().any(|v| v["op"] == "edit"));
            assert_ne!(delegate.keyboard_state().map(|v| v.session_id), Some(id));
        }
    }
    #[tokio::test]
    async fn host_unavailable_and_disabled_never_dispatch_queued_commands() {
        let shared = state();
        let snapshot = Arc::new(Mutex::new(host_snapshot()));
        let calls = Arc::new(Mutex::new(vec![]));
        let mut host = HostDispatcher::new(mock_host(snapshot, calls.clone()));
        let (tx, mut rx) = mpsc::channel(32);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        observe_host(
            &shared,
            &json!({"enabled":true,"connected":false,"keyboard":null}),
        );
        delegate.on_button("home");
        assert!(rx.try_recv().is_err());
        host_command(&mut host, &shared, Command::Button("home".into(), 0))
            .await
            .unwrap();
        assert!(calls.lock().unwrap().is_empty());
        shared.lock().unwrap().connected = true;
        delegate.on_button("home");
        shared.lock().unwrap().enabled = false;
        host_command(&mut host, &shared, rx.try_recv().unwrap())
            .await
            .unwrap();
        assert!(calls.lock().unwrap().is_empty());
        assert_eq!(
            host_call(&mut host, &shared, json!({"op":"state"}))
                .await
                .unwrap_err(),
            "host_disabled"
        );
    }
    #[tokio::test]
    async fn host_completion_after_disable_cannot_restore_field_or_status() {
        let shared = state();
        let started = Arc::new(tokio::sync::Notify::new());
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let release_rx = Mutex::new(release_rx);
        let signal = started.clone();
        let host: HostInput = Arc::new(move |_| {
            signal.notify_one();
            release_rx.lock().unwrap().recv().unwrap();
            Ok(json!({"enabled":true,"connected":true,"keyboard":host_snapshot()}).to_string())
        });
        let mut host = HostDispatcher::new(host);
        let state_copy = shared.clone();
        let task =
            tokio::spawn(
                async move { host_call(&mut host, &state_copy, json!({"op":"state"})).await },
            );
        started.notified().await;
        {
            let mut s = shared.lock().unwrap();
            s.enabled = false;
            s.keyboard = None;
            s.connected = false;
        }
        release_tx.send(()).unwrap();
        assert_eq!(task.await.unwrap().unwrap_err(), "host_disabled");
        assert!(shared.lock().unwrap().keyboard.is_none());
    }
    #[test]
    fn host_snapshot_rejects_unbounded_or_invalid_field_tickets() {
        for (name, value) in [
            ("generation", json!("bad")),
            ("revision", json!(-1)),
            ("selectionEnd", json!(1)),
            ("text", json!("\0")),
            ("ready", json!(false)),
        ] {
            let mut snapshot = host_snapshot();
            snapshot[name] = value;
            assert!(KeyboardSnapshot::host(&snapshot).is_none());
        }
    }
    #[tokio::test]
    async fn host_metadata_noop_skips_write_but_submit_consumes_revision() {
        let shared = state();
        let snapshot = Arc::new(Mutex::new(host_snapshot()));
        let calls = Arc::new(Mutex::new(vec![]));
        let mut host = HostDispatcher::new(mock_host(snapshot.clone(), calls));
        observe_host(
            &shared,
            &json!({"enabled":true,"connected":true,"keyboard":*snapshot.lock().unwrap()}),
        );
        let (tx, mut rx) = mpsc::channel(32);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        let id = delegate.keyboard_state().unwrap().session_id;
        for submit in [false, true] {
            assert!(delegate.keyboard_edit(KeyboardEdit {
                session_id: id.clone(),
                text: String::new(),
                submit
            }));
            if submit {
                host_command(&mut host, &shared, rx.try_recv().unwrap())
                    .await
                    .unwrap();
            } else {
                assert!(rx.try_recv().is_err());
                assert_eq!(snapshot.lock().unwrap()["revision"], 1);
            }
            assert_eq!(delegate.keyboard_state().unwrap().session_id, id);
        }
        assert_eq!(snapshot.lock().unwrap()["revision"], 2);
    }
    #[test]
    fn remote_text_is_not_exposed_or_accepted_for_mid_document_caret_or_selection() {
        for (start, end) in [(0, 0), (1, 1), (0, 3), (3, 1), (3, 3)] {
            let shared = state();
            let mut snapshot = field();
            snapshot.text = "a🎵".into();
            snapshot.selection_start = start;
            snapshot.selection_end = end;
            let field = KeyboardField::new(snapshot);
            let id = field.id.clone();
            shared.lock().unwrap().keyboard = Some(field);
            let (tx, mut rx) = mpsc::channel(8);
            let delegate = Delegate {
                state: shared.clone(),
                tx,
            };
            let supported = start == 3 && end == 3;
            assert_eq!(delegate.keyboard_state().is_some(), supported);
            assert_eq!(
                shared.lock().unwrap().status(vec![])["keyboard"]["ready"],
                supported
            );
            assert_eq!(
                delegate.keyboard_edit(KeyboardEdit {
                    session_id: id,
                    text: "a🎵x".into(),
                    submit: false,
                }),
                supported
            );
            assert_eq!(rx.try_recv().is_ok(), supported);
        }
    }
    #[tokio::test]
    async fn host_arbitrary_failure_is_redacted_at_native_boundary() {
        let shared = state();
        let host: HostInput =
            Arc::new(|_| Ok(json!({"ok":false,"error":"PRIVATE_FIELD_TEXT"}).to_string()));
        let mut host = HostDispatcher::new(host);
        host_command(&mut host, &shared, Command::Button("home".into(), 0))
            .await
            .unwrap();
        let status = shared.lock().unwrap().status(vec![]).to_string();
        assert!(!status.contains("PRIVATE_FIELD_TEXT"));
        assert!(status.contains("host_operation_failed"));
    }
    #[test]
    fn expired_pair_window_clears_pin_without_overwriting_verified_connection() {
        for connected in [false, true] {
            let shared = state();
            let pairings = PairingStore::new(vec![], Arc::new(|_| Ok(()))).unwrap();
            begin_apple_pairing(&shared, &pairings);
            {
                let mut state = shared.lock().unwrap();
                state.expires = now() - 1;
                if connected {
                    state.apple = "connected".into();
                }
            }
            expire_apple_pairing(&shared, &pairings);
            let state = shared.lock().unwrap();
            assert!(state.pin.is_none());
            assert_eq!(state.expires, 0);
            assert_eq!(
                state.apple,
                if connected { "connected" } else { "listening" }
            );
            assert_eq!(state.events.back().unwrap()["action"], "pairing_expired");
        }
    }
    #[tokio::test]
    async fn timed_out_host_drains_before_recovery_and_never_replays_old_input() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let shared = state();
        let calls = Arc::new(Mutex::new(vec![]));
        let entered = Arc::new(AtomicUsize::new(0));
        let active = Arc::new(AtomicUsize::new(0));
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let release_rx = Mutex::new(release_rx);
        let log = calls.clone();
        let count = entered.clone();
        let live = active.clone();
        let callback: HostInput = Arc::new(move |raw| {
            assert_eq!(
                live.fetch_add(1, Ordering::SeqCst),
                0,
                "overlapping JNI operations"
            );
            let request: Value = serde_json::from_str(raw).unwrap();
            log.lock().unwrap().push(request);
            if count.fetch_add(1, Ordering::SeqCst) == 0 {
                release_rx.lock().unwrap().recv().unwrap();
            }
            live.fetch_sub(1, Ordering::SeqCst);
            Ok(
                json!({"ok":true,"enabled":true,"connected":true,"keyboard":host_snapshot()})
                    .to_string(),
            )
        });
        let mut host = HostDispatcher::new(callback);
        host.timeout = Duration::from_millis(100);
        let (tx, mut rx) = mpsc::channel(32);
        let delegate = Delegate {
            state: shared.clone(),
            tx,
        };
        delegate.on_button("home");
        let old_button = rx.try_recv().unwrap();
        let field = delegate.keyboard_state().unwrap();
        assert!(delegate.keyboard_edit(KeyboardEdit {
            session_id: field.session_id,
            text: "STALE".into(),
            submit: false
        }));
        let old_text = rx.try_recv().unwrap();
        assert_eq!(
            host_call(&mut host, &shared, json!({"op":"state"}))
                .await
                .unwrap_err(),
            "host_timeout"
        );
        assert_eq!(entered.load(Ordering::SeqCst), 1);
        host_error(&shared, "host_timeout");
        assert!(!host.settled().await);
        assert_eq!(
            host_call(&mut host, &shared, json!({"op":"state"}))
                .await
                .unwrap_err(),
            "host_draining"
        );
        delegate.on_button("up");
        assert!(rx.try_recv().is_err());
        release_tx.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while !host.settled().await {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert!(
            !shared.lock().unwrap().connected,
            "late response must not restore readiness"
        );
        assert!(shared.lock().unwrap().keyboard.is_none());
        let status = host_call(&mut host, &shared, json!({"op":"state"}))
            .await
            .unwrap();
        observe_host(&shared, &status);
        host_command(&mut host, &shared, old_button).await.unwrap();
        host_command(&mut host, &shared, old_text).await.unwrap();
        assert!(!calls
            .lock()
            .unwrap()
            .iter()
            .any(|v| v["op"] == "key" || v["op"] == "edit"));
        delegate.on_button("right");
        host_command(&mut host, &shared, rx.try_recv().unwrap())
            .await
            .unwrap();
        let calls = calls.lock().unwrap();
        assert_eq!(calls.iter().filter(|v| v["op"] == "key").count(), 1);
        assert_eq!(calls.last().unwrap()["action"], "right");
        assert!(shared
            .lock()
            .unwrap()
            .events
            .iter()
            .any(|v| v["action"] == "host_timeout_state"));
    }
    #[tokio::test]
    async fn completed_host_callback_failure_allows_fresh_state_probe() {
        use std::sync::atomic::{AtomicBool, Ordering};
        let first = AtomicBool::new(true);
        let callback: HostInput = Arc::new(move |_| {
            if first.swap(false, Ordering::SeqCst) {
                Err("PRIVATE_ERROR".into())
            } else {
                Ok(json!({"enabled":true,"connected":true,"keyboard":null}).to_string())
            }
        });
        let shared = state();
        let mut host = HostDispatcher::new(callback);
        assert_eq!(
            host_call(&mut host, &shared, json!({"op":"state"}))
                .await
                .unwrap_err(),
            "host_unavailable"
        );
        host_error(&shared, "host_unavailable");
        assert!(host.settled().await);
        let status = host_call(&mut host, &shared, json!({"op":"state"}))
            .await
            .unwrap();
        observe_host(&shared, &status);
        assert!(shared.lock().unwrap().connected);
        assert!(!shared
            .lock()
            .unwrap()
            .status(vec![])
            .to_string()
            .contains("PRIVATE_ERROR"));
    }
    #[test]
    fn timeout_cancels_a_blocking_job_that_has_not_started() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .max_blocking_threads(1)
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let (started_tx, started_rx) = tokio::sync::oneshot::channel();
            let (release_tx, release_rx) = std::sync::mpsc::channel();
            let busy = tokio::task::spawn_blocking(move || {
                started_tx.send(()).unwrap();
                release_rx.recv().unwrap();
            });
            started_rx.await.unwrap();
            let calls = Arc::new(AtomicUsize::new(0));
            let count = calls.clone();
            let callback: HostInput = Arc::new(move |_| {
                count.fetch_add(1, Ordering::SeqCst);
                Ok(json!({"enabled":true,"connected":true,"keyboard":null}).to_string())
            });
            let mut host = HostDispatcher::new(callback);
            host.timeout = Duration::from_millis(20);
            let shared = state();
            assert_eq!(
                host_call(&mut host, &shared, json!({"op":"key","action":"home"}))
                    .await
                    .unwrap_err(),
                "host_timeout"
            );
            host_error(&shared, "host_timeout");
            release_tx.send(()).unwrap();
            busy.await.unwrap();
            tokio::time::timeout(Duration::from_secs(1), async {
                while !host.settled().await {
                    tokio::task::yield_now().await;
                }
            })
            .await
            .unwrap();
            assert_eq!(
                calls.load(Ordering::SeqCst),
                0,
                "queued key must not execute after its timeout"
            );
            host_call(&mut host, &shared, json!({"op":"state"}))
                .await
                .unwrap();
            assert_eq!(calls.load(Ordering::SeqCst), 1);
        });
    }
}
