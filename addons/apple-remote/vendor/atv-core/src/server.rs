//! Server: TCP listeners and Bonjour (mDNS) advertisement.
//!
//! Ported from `fake_atv.py`'s `main()`: three services with the exact TXT
//! records iOS expects (`rpFl=0x36782` etc. gate features like the volume
//! keys), the MRP port answering minimal framing, and the AirPlay port
//! answering HTTP "OK".

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[cfg(not(target_os = "macos"))]
use mdns_sd::{ServiceDaemon, ServiceInfo};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{watch, Mutex};
use tokio::task::JoinHandle;
use tracing::{debug, info, warn};

use sha2::{Digest, Sha256};

use crate::delegate::{AtvDelegate, EventKind, TrackpadMode};
use crate::error::{Error, Result};
use crate::identity::{self, DeviceIdentity};
use crate::inspector::InspectorHub;
use crate::pairing::PairingStore;
use crate::registry::{ActiveConnectionTracker, DeviceRegistry};
use crate::session::{CompanionSession, DEVICE_MODEL};
use crate::FrameType;

pub const MRP_PORT: u16 = 49152;
pub const COMPANION_PORT: u16 = 49153;
pub const SOURCE_VERSION: &str = "715.2";
pub const DEFAULT_DEVICE_NAME: &str = "Mac Remote";

/// Query the local Mac's computer name via `scutil --get ComputerName`.
pub fn detect_mac_computer_name() -> Option<String> {
    let name = identity::detect_computer_name();
    if name == DEFAULT_DEVICE_NAME {
        None
    } else {
        Some(name)
    }
}

/// Default device name: uses local Mac computer name on macOS, otherwise "Mac Remote".
pub fn default_device_name() -> String {
    identity::detect_computer_name()
}

/// Server configuration.
#[derive(Clone)]
pub struct AtvConfig {
    pub pairings: Arc<PairingStore>,
    /// Device name shown in Apple TV Remote.
    pub name: String,
    /// Legacy compatibility field; PINs come only from PairingStore::open_pairing.
    pub pin: u32,
    /// LAN IPv4 address to advertise; auto-detected when None.
    pub ip: Option<Ipv4Addr>,
    /// Trackpad mode: touch drives pointer deltas instead of direction keys (legacy boolean).
    pub mouse_mode: bool,
    /// Operating mode: Direction, Mouse, or Idle (preview only).
    pub trackpad_mode: TrackpadMode,
    /// Must be None. Production startup rejects the upstream debug web server.
    pub ui_port: Option<u16>,
    /// Initial mouse speed multiplier (default 0.5).
    pub mouse_speed: f64,
    /// Hardware / MAC-like identifier (e.g. "84:2F:57:2E:6C:EE").
    pub device_id: Option<String>,
    /// Unique UUID (e.g. "F232C260-003A-5396-99D1-F8164BB03DF2").
    pub server_identifier: Option<String>,
    /// Persistent private key seed (32 bytes).
    pub private_key: Option<[u8; 32]>,
}

impl Default for AtvConfig {
    fn default() -> Self {
        let identity = DeviceIdentity::detect();
        let settings = crate::settings::UserSettings::load();
        let initial_mode = settings
            .trackpad_mode
            .as_deref()
            .and_then(TrackpadMode::from_str_opt)
            .unwrap_or_else(|| {
                if settings.mouse_mode.unwrap_or(false) {
                    TrackpadMode::Mouse
                } else {
                    TrackpadMode::Direction
                }
            });
        Self {
            pairings: Arc::new(
                PairingStore::new(vec![], Arc::new(|_| Err("host storage required".into())))
                    .expect("empty pairing store"),
            ),
            name: identity.name,
            pin: 0,
            ip: None,
            mouse_mode: initial_mode.is_mouse(),
            trackpad_mode: initial_mode,
            ui_port: None,
            mouse_speed: settings.mouse_speed,
            device_id: Some(identity.device_id),
            server_identifier: Some(identity.server_identifier),
            private_key: None,
        }
    }
}

/// A running Apple TV simulator. Drop or call `stop()` to shut down.
pub struct AtvServer {
    config: AtvConfig,
    identity: DeviceIdentity,
    ip: Ipv4Addr,
    mrp_port: u16,
    companion_port: u16,
    airplay_port: u16,
    ui_port: Option<u16>,
    inspector: Arc<InspectorHub>,
    mouse_mode: Arc<AtomicBool>,
    trackpad_mode: Arc<AtomicU8>,
    delegate: Arc<dyn AtvDelegate>,
    shutdown_tx: watch::Sender<bool>,
    tasks: Vec<JoinHandle<()>>,
    #[cfg(target_os = "macos")]
    dns_sd_children: Vec<tokio::process::Child>,
    #[cfg(not(target_os = "macos"))]
    mdns: ServiceDaemon,
    #[cfg(not(target_os = "macos"))]
    mdns_fullnames: Vec<String>,
}

impl AtvServer {
    /// Start listeners and advertise the three Bonjour services.
    pub async fn start(config: AtvConfig, delegate: Arc<dyn AtvDelegate>) -> Result<Self> {
        let trackpad_mode = Arc::new(AtomicU8::new(config.trackpad_mode as u8));
        let mouse_mode = Arc::new(AtomicBool::new(config.trackpad_mode.is_mouse()));
        Self::start_with_trackpad_mode(config, delegate, trackpad_mode, mouse_mode).await
    }

    /// Start listeners with a shared atomic mouse_mode boolean for live mode switching (legacy helper).
    pub async fn start_with_mouse_mode(
        config: AtvConfig,
        delegate: Arc<dyn AtvDelegate>,
        mouse_mode: Arc<AtomicBool>,
    ) -> Result<Self> {
        let initial_u8 = if mouse_mode.load(Ordering::SeqCst) {
            TrackpadMode::Mouse as u8
        } else {
            config.trackpad_mode as u8
        };
        let trackpad_mode = Arc::new(AtomicU8::new(initial_u8));
        Self::start_with_trackpad_mode(config, delegate, trackpad_mode, mouse_mode).await
    }

    /// Start listeners with shared atomic mode trackers.
    pub async fn start_with_trackpad_mode(
        config: AtvConfig,
        delegate: Arc<dyn AtvDelegate>,
        trackpad_mode: Arc<AtomicU8>,
        mouse_mode: Arc<AtomicBool>,
    ) -> Result<Self> {
        if config.private_key.is_none() || config.ui_port.is_some() {
            return Err(Error::Protocol(
                "host identity required; debug listener disabled".into(),
            ));
        }
        if config.pin > 9999 {
            return Err(Error::Protocol("pin must be between 0000 and 9999".into()));
        }
        let identity = DeviceIdentity::from_config(&config);
        let mut config = config;
        config.name = identity.name.clone();
        config.device_id = Some(identity.device_id.clone());
        config.server_identifier = Some(identity.server_identifier.clone());
        config.private_key = Some(identity.private_key);

        let ip = match config.ip {
            Some(ip) => ip,
            None => detect_local_ip()?,
        };

        let (mrp_listener, mrp_port) = bind_with_fallback(MRP_PORT, "MRP").await?;
        let (companion_listener, companion_port) =
            bind_with_fallback(COMPANION_PORT, "Companion").await?;
        // Port 7000 is normally occupied when macOS AirPlay Receiver is on;
        // we only need this endpoint for discovery, so let the OS choose.
        let airplay_listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).await?;
        let airplay_port = airplay_listener.local_addr()?.port();

        let inspector = InspectorHub::new();

        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let mut tasks = Vec::new();

        let rpha = identity.device_id.replace(':', "").to_uppercase();
        let airplay_id = {
            let mut h = Sha256::new();
            h.update(b"airplay-identity:");
            h.update(identity.server_identifier.as_bytes());
            hex::encode(&h.finalize()[..8]).to_uppercase()
        };
        let rphn = {
            let mut h = Sha256::new();
            h.update(b"rpHN:");
            h.update(identity.server_identifier.as_bytes());
            hex::encode(&h.finalize()[..6]).to_uppercase()
        };
        let rpad = {
            let mut h = Sha256::new();
            h.update(b"rpAD:");
            h.update(identity.server_identifier.as_bytes());
            hex::encode(&h.finalize()[..6]).to_uppercase()
        };
        let rphi = {
            let mut h = Sha256::new();
            h.update(b"rpHI:");
            h.update(identity.server_identifier.as_bytes());
            hex::encode(&h.finalize()[..6]).to_uppercase()
        };
        let pk = {
            let mut h = Sha256::new();
            h.update(b"pk:");
            h.update(identity.server_identifier.as_bytes());
            hex::encode(&h.finalize()[..16])
        };

        let services: Vec<(&str, u16, Vec<(&str, &str)>)> = vec![
            (
                "_mediaremotetv._tcp.local.",
                mrp_port,
                vec![
                    ("Name", identity.name.as_str()),
                    ("UniqueIdentifier", identity.unique_id.as_str()),
                    ("SystemBuildVersion", "22K160"),
                    ("LocalAirPlayReceiverPairingIdentity", airplay_id.as_str()),
                    ("ModelName", "Apple TV"),
                    ("AllowPairing", "YES"),
                ],
            ),
            (
                "_companion-link._tcp.local.",
                companion_port,
                vec![
                    ("rpMac", "1"),
                    ("rpHA", rpha.as_str()),
                    ("rpHN", rphn.as_str()),
                    ("rpVr", SOURCE_VERSION),
                    ("rpMd", DEVICE_MODEL),
                    ("rpFl", "0x36782"),
                    ("rpAD", rpad.as_str()),
                    ("rpHI", rphi.as_str()),
                    ("rpBA", identity.device_id.as_str()),
                ],
            ),
            (
                "_airplay._tcp.local.",
                airplay_port,
                vec![
                    ("deviceid", identity.device_id.as_str()),
                    ("features", "0x5A7FFFF7,0x1E"),
                    ("flags", "0x44"),
                    ("model", DEVICE_MODEL),
                    ("srcvers", SOURCE_VERSION),
                    ("vv", "2"),
                    ("pi", identity.server_identifier.as_str()),
                    ("pk", pk.as_str()),
                    ("name", identity.name.as_str()),
                ],
            ),
        ];

        #[cfg(target_os = "macos")]
        let mut dns_sd_children = Vec::new();
        #[cfg(target_os = "macos")]
        {
            for (service_type, port, properties) in &services {
                let dns_sd_type = service_type
                    .trim_end_matches(".local.")
                    .trim_end_matches(".local");
                let mut cmd = tokio::process::Command::new("/usr/bin/dns-sd");
                cmd.arg("-R")
                    .arg(&identity.name)
                    .arg(dns_sd_type)
                    .arg("local.")
                    .arg(port.to_string());
                for (k, v) in properties {
                    cmd.arg(format!("{k}={v}"));
                }
                cmd.kill_on_drop(true);
                cmd.stdout(std::process::Stdio::null());
                cmd.stderr(std::process::Stdio::null());
                match cmd.spawn() {
                    Ok(child) => {
                        dns_sd_children.push(child);
                        info!(
                            "registered {}.{service_type} on {ip}:{port} (via macOS mDNSResponder)",
                            identity.name
                        );
                    }
                    Err(e) => {
                        warn!("failed to spawn /usr/bin/dns-sd for {service_type}: {e}");
                    }
                }
            }
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        }

        #[cfg(not(target_os = "macos"))]
        let mdns = ServiceDaemon::new().map_err(|e| Error::Mdns(e.to_string()))?;
        #[cfg(not(target_os = "macos"))]
        let mut mdns_fullnames = Vec::new();
        #[cfg(not(target_os = "macos"))]
        {
            let host_name = format!("{}.local.", identity.name.replace(' ', "-"));
            for (service_type, port, properties) in services {
                let info = ServiceInfo::new(
                    service_type,
                    &identity.name,
                    &host_name,
                    ip.to_string(),
                    port,
                    &properties[..],
                )
                .map_err(|e| {
                    let _ = mdns.shutdown();
                    Error::Mdns(e.to_string())
                })?;
                mdns_fullnames.push(info.get_fullname().to_string());
                if let Err(e) = mdns.register(info) {
                    let _ = mdns.shutdown();
                    return Err(Error::Mdns(e.to_string()));
                }
                info!("registered {}.{service_type} on {ip}:{port}", identity.name);
            }
        }

        tasks.push(tokio::spawn(mrp_loop(
            mrp_listener,
            shutdown_rx.clone(),
            delegate.clone(),
        )));
        tasks.push(tokio::spawn(airplay_loop(
            airplay_listener,
            shutdown_rx.clone(),
            delegate.clone(),
        )));
        tasks.push(tokio::spawn(companion_loop(
            companion_listener,
            identity.clone(),
            config.pairings.clone(),
            delegate.clone(),
            inspector.clone(),
            trackpad_mode.clone(),
            mouse_mode.clone(),
            shutdown_rx.clone(),
        )));

        let actual_ui_port = None;

        info!("fake Apple TV started");
        info!("Name      : \"{}\"", identity.name);
        info!("Device ID : {}", identity.device_id);
        info!("Server ID : {}", identity.server_identifier);

        info!("MRP       : {ip}:{mrp_port}");
        info!("Companion : {ip}:{companion_port}");
        info!("AirPlay   : {ip}:{airplay_port}");

        Ok(Self {
            config,
            identity,
            ip,
            mrp_port,
            companion_port,
            airplay_port,
            ui_port: actual_ui_port,
            inspector,
            mouse_mode,
            trackpad_mode,
            delegate,
            shutdown_tx,
            tasks,
            #[cfg(target_os = "macos")]
            dns_sd_children,
            #[cfg(not(target_os = "macos"))]
            mdns,
            #[cfg(not(target_os = "macos"))]
            mdns_fullnames,
        })
    }

    pub fn config(&self) -> &AtvConfig {
        &self.config
    }

    pub fn identity(&self) -> &DeviceIdentity {
        &self.identity
    }

    /// The LAN address the services advertise.
    pub fn ip(&self) -> Ipv4Addr {
        self.ip
    }

    pub fn mrp_port(&self) -> u16 {
        self.mrp_port
    }

    pub fn companion_port(&self) -> u16 {
        self.companion_port
    }

    pub fn airplay_port(&self) -> u16 {
        self.airplay_port
    }

    pub fn ui_port(&self) -> Option<u16> {
        self.ui_port
    }

    pub fn inspector(&self) -> Arc<InspectorHub> {
        self.inspector.clone()
    }

    pub fn trackpad_mode_arc(&self) -> Arc<AtomicU8> {
        self.trackpad_mode.clone()
    }

    pub fn trackpad_mode(&self) -> TrackpadMode {
        TrackpadMode::from_u8(self.trackpad_mode.load(Ordering::SeqCst))
    }

    pub fn set_trackpad_mode(&self, mode: TrackpadMode) {
        self.trackpad_mode.store(mode as u8, Ordering::SeqCst);
        self.mouse_mode.store(mode.is_mouse(), Ordering::SeqCst);
        self.delegate.on_trackpad_mode_changed(mode);
        self.inspector.emit(
            "mode_changed",
            &format!(
                "{{\"mode\":\"{}\",\"mouse_mode\":{}}}",
                mode.as_str(),
                mode.is_mouse()
            ),
        );
        crate::settings::UserSettings::update_trackpad_mode(mode.as_str());
    }

    pub fn cycle_trackpad_mode(&self) -> TrackpadMode {
        let current = self.trackpad_mode();
        let new_mode = current.next();
        self.set_trackpad_mode(new_mode);
        new_mode
    }

    pub fn mouse_mode_arc(&self) -> Arc<AtomicBool> {
        self.mouse_mode.clone()
    }

    pub fn is_mouse_mode(&self) -> bool {
        self.mouse_mode.load(Ordering::SeqCst)
    }

    pub fn is_idle_mode(&self) -> bool {
        self.trackpad_mode.load(Ordering::SeqCst) == TrackpadMode::Idle as u8
    }

    pub fn set_mouse_mode(&self, enabled: bool) {
        self.set_trackpad_mode(if enabled {
            TrackpadMode::Mouse
        } else {
            TrackpadMode::Direction
        });
    }

    pub fn toggle_mouse_mode(&self) -> bool {
        let new_mode = self.cycle_trackpad_mode();
        new_mode.is_mouse()
    }

    /// Unregister Bonjour services and stop all listeners.
    pub async fn stop(mut self) {
        let _ = self.shutdown_tx.send(true);

        #[cfg(target_os = "macos")]
        for mut child in self.dns_sd_children.drain(..) {
            let _ = child.kill().await;
        }

        #[cfg(not(target_os = "macos"))]
        {
            for fullname in &self.mdns_fullnames {
                if let Err(e) = self.mdns.unregister(fullname) {
                    warn!("mDNS unregister failed for {fullname}: {e}");
                }
            }
            if let Err(e) = self.mdns.shutdown() {
                warn!("mDNS shutdown failed: {e}");
            }
        }

        for task in &self.tasks {
            task.abort();
        }
        for task in self.tasks.drain(..) {
            let _ = task.await;
        }
    }
}

impl Drop for AtvServer {
    fn drop(&mut self) {
        let _ = self.shutdown_tx.send(true);
        for task in &self.tasks {
            task.abort();
        }
        #[cfg(target_os = "macos")]
        for child in &mut self.dns_sd_children {
            let _ = child.start_kill();
        }
        #[cfg(not(target_os = "macos"))]
        {
            for fullname in &self.mdns_fullnames {
                let _ = self.mdns.unregister(fullname);
            }
            let _ = self.mdns.shutdown();
        }
    }
}

/// Bind `preferred`, falling back to an OS-assigned port when it is occupied.
/// The chosen port is advertised via Bonjour, so iOS finds the service either way.
async fn bind_with_fallback(preferred: u16, label: &str) -> Result<(TcpListener, u16)> {
    match TcpListener::bind((Ipv4Addr::UNSPECIFIED, preferred)).await {
        Ok(listener) => Ok((listener, preferred)),
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {
            let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).await?;
            let port = listener.local_addr()?.port();
            warn!(
                "{label} port {preferred} is already in use ({e}); \
                 falling back to ephemeral port {port}"
            );
            Ok((listener, port))
        }
        Err(e) => Err(e.into()),
    }
}

/// Return the IPv4 address used to reach the local network (UDP-connect
/// trick, like `fake_atv.detect_local_ip`).
pub fn detect_local_ip() -> Result<Ipv4Addr> {
    let sock = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))?;
    // A UDP connect sends nothing; it asks the kernel which route it would use.
    if let Ok(()) = sock.connect((Ipv4Addr::new(192, 0, 2, 1), 9)) {
        if let Ok(addr) = sock.local_addr() {
            if let std::net::IpAddr::V4(ip) = addr.ip() {
                if !ip.is_unspecified() && !ip.is_loopback() {
                    return Ok(ip);
                }
            }
        }
    }
    if let Ok(()) = sock.connect((Ipv4Addr::new(8, 8, 8, 8), 53)) {
        if let Ok(addr) = sock.local_addr() {
            if let std::net::IpAddr::V4(ip) = addr.ip() {
                if !ip.is_unspecified() && !ip.is_loopback() {
                    return Ok(ip);
                }
            }
        }
    }
    Err(Error::Protocol(
        "unable to detect local IPv4 address".into(),
    ))
}

async fn companion_loop(
    listener: TcpListener,
    identity: DeviceIdentity,
    pairings: Arc<PairingStore>,
    delegate: Arc<dyn AtvDelegate>,
    inspector: Arc<InspectorHub>,
    trackpad_mode: Arc<AtomicU8>,
    mouse_mode: Arc<AtomicBool>,
    mut shutdown: watch::Receiver<bool>,
) {
    let registry = Arc::new(Mutex::new(DeviceRegistry::new(Duration::from_secs(
        24 * 3600,
    ))));
    let active_tracker = Arc::new(Mutex::new(ActiveConnectionTracker::new()));

    let slots = Arc::new(tokio::sync::Semaphore::new(4));
    loop {
        let stream = tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, _)) => stream,
                Err(e) => {
                    warn!("companion accept failed: {e}");
                    continue;
                }
            },
            _ = shutdown.changed() => break,
        };
        let permit = match slots.clone().try_acquire_owned() {
            Ok(permit) => permit,
            Err(_) => continue,
        };
        let pairings = pairings.clone();
        let identity = identity.clone();
        let delegate = delegate.clone();
        let inspector = inspector.clone();
        let trackpad_mode = trackpad_mode.clone();
        let mouse_mode = mouse_mode.clone();
        let registry = registry.clone();
        let active_tracker = active_tracker.clone();
        let mut client_shutdown = shutdown.clone();
        tokio::spawn(async move {
            let _permit = permit;
            tokio::select! {
                _ = handle_companion(stream, identity, pairings, delegate, inspector, trackpad_mode, mouse_mode, registry, active_tracker) => {},
                _ = client_shutdown.changed() => {},
            }
        });
    }
}

async fn handle_companion(
    stream: TcpStream,
    identity: DeviceIdentity,
    pairings: Arc<PairingStore>,
    delegate: Arc<dyn AtvDelegate>,
    inspector: Arc<InspectorHub>,
    trackpad_mode: Arc<AtomicU8>,
    mouse_mode: Arc<AtomicBool>,
    registry: Arc<Mutex<DeviceRegistry>>,
    active_tracker: Arc<Mutex<ActiveConnectionTracker>>,
) {
    let peer_addr = stream.peer_addr().ok();
    let peer = peer_addr
        .as_ref()
        .map(|a| a.to_string())
        .unwrap_or_else(|| "?".into());
    let peer_ip = peer_addr
        .map(|a| a.ip())
        .unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED));

    info!("companion client connected: {peer}");
    inspector.add_client(&peer);
    delegate.on_event(EventKind::ClientConnected, &peer);
    inspector.emit("client_connected", &format!("{{\"peer\":\"{peer}\"}}"));

    let session_established = Arc::new(AtomicBool::new(false));
    let (conn_id, mut abort_rx) = {
        let mut tracker = active_tracker.lock().await;
        tracker.register(peer_ip, session_established.clone())
    };

    let (mut reader, writer) = stream.into_split();
    let writer = Arc::new(Mutex::new(writer));
    let mut session = CompanionSession::new(
        writer,
        delegate.clone(),
        &identity,
        pairings,
        mouse_mode,
        trackpad_mode,
        inspector.clone(),
        registry,
        active_tracker.clone(),
        conn_id,
        peer_ip.to_string(),
        session_established,
    );
    let timeout_deadline = session.timeout_deadline();
    let mut disconnect_reason = "peer_closed".to_string();

    const MAX_FRAME_LENGTH: usize = 64 * 1024;
    let mut ticker = tokio::time::interval(std::time::Duration::from_millis(250));
    'connection: loop {
        let mut header = [0u8; 4];
        // Keep this same read future alive across ticks: read_exact is not
        // cancellation-safe when a TCP header arrives in fragments.
        {
            let read = reader.read_exact(&mut header);
            tokio::pin!(read);
            loop {
                tokio::select! {
                    res=&mut read => {
                        if res.is_err() {break 'connection;}
                        break;
                    }
                    _=abort_rx.changed()=> {disconnect_reason="superseded".into();break 'connection;}
                    _=ticker.tick()=> {
                        if timeout_deadline.lock().await.is_some_and(|d|d<=tokio::time::Instant::now()) {
                            disconnect_reason="sessionless_timeout".into();break 'connection;
                        }
                        if session.tick().await.is_err() {disconnect_reason="session_unavailable".into();break 'connection;}
                    }
                }
            }
        }
        let Some(frame_type) = FrameType::from_u8(header[0]) else {
            delegate.on_event(EventKind::UnsupportedFrame, "");
            disconnect_reason = format!("error:unknown frame type {}", header[0]);
            warn!("unknown frame type {}", header[0]);
            break;
        };
        let length = u32::from_be_bytes([0, header[1], header[2], header[3]]) as usize;
        if length > MAX_FRAME_LENGTH {
            disconnect_reason = format!("error:frame too large ({length} bytes)");
            warn!("companion frame exceeds limit: {length} bytes from {peer}");
            break;
        }
        let mut payload = vec![0u8; length];
        if !matches!(
            tokio::time::timeout(
                std::time::Duration::from_secs(5),
                reader.read_exact(&mut payload)
            )
            .await,
            Ok(Ok(_))
        ) {
            disconnect_reason = "incomplete_frame".into();
            break;
        }
        debug!("companion frame={:?} length={length}", frame_type);
        match frame_type {
            FrameType::PsStart => delegate.on_event(EventKind::PairSetupStarted, ""),
            FrameType::PvStart => delegate.on_event(EventKind::PairVerifyStarted, ""),
            _ => {}
        }
        if let Err(e) = session.handle_frame(frame_type, &payload, &header).await {
            delegate.on_event(rejected_frame_event(frame_type), "");
            if matches!(
                frame_type,
                FrameType::PsStart | FrameType::PsNext | FrameType::PvStart | FrameType::PvNext
            ) {
                delegate.on_event(authentication_error_event(&e), "");
            }
            disconnect_reason = format!("error:{e}");
            warn!("companion error: {e}");
            break;
        }
    }

    {
        let mut tracker = active_tracker.lock().await;
        tracker.unregister(conn_id);
    }

    info!("companion client disconnected: {peer}");
    inspector.remove_client(&peer);
    let reason_event = match disconnect_reason.as_str() {
        "peer_closed" => Some(EventKind::ClientPeerClosed),
        "sessionless_timeout" => Some(EventKind::ClientSessionlessTimeout),
        "superseded" => Some(EventKind::ClientSuperseded),
        "session_unavailable" => Some(EventKind::ClientSessionUnavailable),
        "incomplete_frame" => Some(EventKind::ClientIncompleteFrame),
        _ => None,
    };
    if let Some(kind) = reason_event {
        delegate.on_event(kind, "");
    }
    delegate.on_event(
        EventKind::ClientDisconnected,
        &format!("{peer} reason={disconnect_reason}"),
    );
    inspector.emit(
        "client_disconnected",
        &format!("{{\"peer\":\"{peer}\",\"reason\":\"{disconnect_reason}\"}}"),
    );
}

fn authentication_error_event(error: &Error) -> EventKind {
    match error {
        Error::Protocol(message)
            if matches!(
                message.as_str(),
                "unexpected pair-setup"
                    | "unexpected pair-verify"
                    | "unexpected verify step"
                    | "unverified pair-setup"
            ) =>
        {
            EventKind::AuthenticationStateRejected
        }
        Error::Protocol(message)
            if matches!(
                message.as_str(),
                "pairing window closed"
                    | "pairing window expired"
                    | "unexpected or expired pair-setup"
            ) =>
        {
            EventKind::PairingWindowClosed
        }
        Error::Protocol(message) if message == "pairing attempt limit" => {
            EventKind::PairingAttemptLimit
        }
        Error::Protocol(message) if message == "unknown or revoked client" => {
            EventKind::UnknownClientRejected
        }
        Error::Crypto(message) if message == "client signature rejected" => {
            EventKind::AuthenticationSignatureRejected
        }
        Error::Crypto(_) => EventKind::AuthenticationCryptoRejected,
        Error::Srp(_) => EventKind::AuthenticationProofRejected,
        Error::Io(_) => EventKind::AuthenticationTransportError,
        _ => EventKind::AuthenticationPayloadRejected,
    }
}

fn rejected_frame_event(frame_type: FrameType) -> EventKind {
    match frame_type {
        FrameType::PsStart | FrameType::PsNext => EventKind::PairSetupRejected,
        FrameType::PvStart | FrameType::PvNext => EventKind::PairVerifyRejected,
        FrameType::EOpack => EventKind::EncryptedControlRejected,
        _ => EventKind::UnsupportedFrame,
    }
}

async fn mrp_loop(
    listener: TcpListener,
    mut shutdown: watch::Receiver<bool>,
    delegate: Arc<dyn AtvDelegate>,
) {
    let limit = Arc::new(tokio::sync::Semaphore::new(4));
    loop {
        let stream = tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, _)) => stream,
                Err(e) => {
                    warn!("MRP accept failed: {e}");
                    continue;
                }
            },
            _ = shutdown.changed() => break,
        };
        let Ok(permit) = limit.clone().try_acquire_owned() else {
            continue;
        };
        delegate.on_event(EventKind::MrpClientConnected, "");
        let mut stop = shutdown.clone();
        tokio::spawn(async move {
            let _permit = permit;
            tokio::select! {
                _=tokio::time::timeout(std::time::Duration::from_secs(10),handle_mrp(stream))=>{},
                _=stop.changed()=>{},
            }
        });
    }
}

async fn handle_mrp(mut stream: TcpStream) {
    let peer = peer_string(&stream);
    info!("MRP client connected: {peer}");
    loop {
        let Some(size) = read_varint(&mut stream).await else {
            break;
        };
        if size > 65536 {
            break;
        }
        let mut body = vec![0u8; size];
        if stream.read_exact(&mut body).await.is_err() {
            break;
        }
        debug!("MRP message size={size}");
    }
    info!("MRP client disconnected: {peer}");
}

async fn read_varint(stream: &mut TcpStream) -> Option<usize> {
    let mut shift = 0u32;
    let mut result: usize = 0;
    loop {
        let mut byte = [0u8; 1];
        stream.read_exact(&mut byte).await.ok()?;
        result |= ((byte[0] & 0x7F) as usize) << shift;
        if byte[0] & 0x80 == 0 {
            return Some(result);
        }
        shift += 7;
        if shift >= usize::BITS {
            warn!("varint too long");
            return None;
        }
    }
}

async fn airplay_loop(
    listener: TcpListener,
    mut shutdown: watch::Receiver<bool>,
    delegate: Arc<dyn AtvDelegate>,
) {
    let limit = Arc::new(tokio::sync::Semaphore::new(4));
    loop {
        let stream = tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, _)) => stream,
                Err(e) => {
                    warn!("AirPlay accept failed: {e}");
                    continue;
                }
            },
            _ = shutdown.changed() => break,
        };
        let Ok(permit) = limit.clone().try_acquire_owned() else {
            continue;
        };
        delegate.on_event(EventKind::AirplayClientConnected, "");
        let mut stop = shutdown.clone();
        tokio::spawn(async move {
            let _permit = permit;
            tokio::select! {
                _=tokio::time::timeout(std::time::Duration::from_secs(10),handle_airplay(stream))=>{},
                _=stop.changed()=>{},
            }
        });
    }
}

async fn handle_airplay(mut stream: TcpStream) {
    let peer = peer_string(&stream);
    info!("AirPlay client connected: {peer}");
    let mut buf = [0u8; 4096];
    let _ = stream.read(&mut buf).await;
    use tokio::io::AsyncWriteExt;
    let _ = stream
        .write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nOK")
        .await;
}

fn peer_string(stream: &TcpStream) -> String {
    stream
        .peer_addr()
        .map(|a: SocketAddr| a.to_string())
        .unwrap_or_else(|_| "?".into())
}

#[cfg(test)]
mod diagnostic_tests {
    use super::*;
    use tokio::io::AsyncWriteExt;
    #[derive(Default)]
    struct Events(std::sync::Mutex<Vec<EventKind>>);
    impl AtvDelegate for Events {
        fn on_event(&self, kind: EventKind, _detail: &str) {
            self.0.lock().unwrap().push(kind);
        }
        fn on_button(&self, _: &str) {
            panic!("unauthenticated input");
        }
    }
    #[tokio::test]
    async fn compatibility_endpoint_connections_are_distinct_from_companion_authentication() {
        for airplay in [false, true] {
            let events = Arc::new(Events::default());
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let (stop, rx) = watch::channel(false);
            let task = if airplay {
                tokio::spawn(airplay_loop(listener, rx, events.clone()))
            } else {
                tokio::spawn(mrp_loop(listener, rx, events.clone()))
            };
            let client = TcpStream::connect(address).await.unwrap();
            tokio::time::timeout(Duration::from_secs(1), async {
                while events.0.lock().unwrap().is_empty() {
                    tokio::task::yield_now().await;
                }
            })
            .await
            .unwrap();
            assert_eq!(
                *events.0.lock().unwrap(),
                vec![if airplay {
                    EventKind::AirplayClientConnected
                } else {
                    EventKind::MrpClientConnected
                }]
            );
            stop.send(true).unwrap();
            task.await.unwrap();
            drop(client);
        }
    }
    #[tokio::test]
    async fn malformed_auth_and_unknown_frames_emit_only_fixed_stage_events() {
        for (frame, stage) in [
            (3, EventKind::PairSetupRejected),
            (5, EventKind::PairVerifyRejected),
            (8, EventKind::EncryptedControlRejected),
            (0x10, EventKind::UnsupportedFrame),
        ] {
            let events = Arc::new(Events::default());
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let mut client = TcpStream::connect(listener.local_addr().unwrap())
                .await
                .unwrap();
            let (socket, _) = listener.accept().await.unwrap();
            let identity = DeviceIdentity {
                name: "test".into(),
                device_id: "02:00:00:00:00:01".into(),
                mac_bytes: [2, 0, 0, 0, 0, 1],
                server_identifier: "server".into(),
                unique_id: "server".into(),
                private_key: [3; 32],
            };
            let task = tokio::spawn(handle_companion(
                socket,
                identity,
                Arc::new(PairingStore::new(vec![], Arc::new(|_| Ok(()))).unwrap()),
                events.clone(),
                InspectorHub::new(),
                Arc::new(AtomicU8::new(0)),
                Arc::new(AtomicBool::new(false)),
                Arc::new(Mutex::new(DeviceRegistry::new(Duration::from_secs(60)))),
                Arc::new(Mutex::new(ActiveConnectionTracker::new())),
            ));
            client.write_all(&[frame, 0, 0, 1, 0xff]).await.unwrap();
            tokio::time::timeout(Duration::from_secs(1), task)
                .await
                .unwrap()
                .unwrap();
            let list = events.0.lock().unwrap();
            assert!(list.contains(&stage));
            assert!(!list.contains(&EventKind::Paired));
            assert!(!list.contains(&EventKind::Verified));
            if frame == 3 {
                assert!(list.contains(&EventKind::PairSetupStarted));
            }
            if frame == 5 {
                assert!(list.contains(&EventKind::PairVerifyStarted));
            }
        }
    }
}
