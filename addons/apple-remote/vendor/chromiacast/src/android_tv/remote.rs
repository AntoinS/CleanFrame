use std::fmt;
use std::net::{IpAddr, SocketAddr};

use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::broadcast;

use super::connection::connect;
use super::error::AndroidTvError;
use super::framing::{FramedReader, FramedWriter};
use super::identity::AndroidTvRemoteIdentity;
use super::ime::ImeEdit;
use super::remote_session::{EstablishedRemote, RemoteSession};
use super::remote_types::{
    AndroidTvDeviceInfo, AndroidTvImeState, AndroidTvKeyAction, AndroidTvKeyCode,
    AndroidTvRemoteClientInfo, AndroidTvRemoteEvent, AndroidTvRemoteFeatures, AndroidTvVolume,
};
use super::ANDROID_TV_REMOTE_PORT;

/// Ready Android TV Remote Service v2 connection.
///
/// The handle sends bounded commands to a private Tokio task which exclusively
/// owns the framed TLS stream. Dropping the handle cancels that task; call
/// [`close`](Self::close) when an orderly TLS shutdown is required.
pub struct AndroidTvRemote {
    session: RemoteSession,
    device_info: AndroidTvDeviceInfo,
    features: AndroidTvRemoteFeatures,
}

impl fmt::Debug for AndroidTvRemote {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("AndroidTvRemote")
            .field("device_info", &self.device_info)
            .field("features", &self.features)
            .field("powered_on", &self.powered_on())
            .field("volume", &self.volume())
            .finish_non_exhaustive()
    }
}

impl AndroidTvRemote {
    /// Connect to the standard Remote Service port at `ip`.
    pub async fn connect(
        ip: IpAddr,
        identity: &AndroidTvRemoteIdentity,
        client_info: AndroidTvRemoteClientInfo,
        server_pin: [u8; 32],
    ) -> Result<Self, AndroidTvError> {
        Self::connect_address(
            SocketAddr::new(ip, ANDROID_TV_REMOTE_PORT),
            identity,
            client_info,
            server_pin,
        )
        .await
    }

    /// Connect to an explicit Remote Service endpoint.
    pub async fn connect_address(
        address: SocketAddr,
        identity: &AndroidTvRemoteIdentity,
        client_info: AndroidTvRemoteClientInfo,
        server_pin: [u8; 32],
    ) -> Result<Self, AndroidTvError> {
        let stream = connect(address, identity, Some(server_pin)).await?;
        let (read_half, write_half) = tokio::io::split(stream);
        Self::start(
            FramedReader::new(read_half),
            FramedWriter::new(write_half),
            client_info,
        )
        .await
    }

    async fn start<R, W>(
        reader: FramedReader<R>,
        writer: FramedWriter<W>,
        client_info: AndroidTvRemoteClientInfo,
    ) -> Result<Self, AndroidTvError>
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        let EstablishedRemote {
            session,
            device_info,
            features,
        } = RemoteSession::start(reader, writer, &client_info).await?;
        Ok(Self {
            session,
            device_info,
            features,
        })
    }

    /// Metadata advertised by the connected Remote Service.
    pub fn device_info(&self) -> &AndroidTvDeviceInfo {
        &self.device_info
    }

    /// Capabilities both advertised by the device and implemented here.
    pub fn features(&self) -> AndroidTvRemoteFeatures {
        self.features
    }

    /// Whether the owning transport task is still running. This does not probe
    /// the peer; it detects completed or panicked tasks even when their final
    /// event occurred before the caller subscribed.
    pub fn is_connected(&self) -> bool {
        self.session.is_connected()
    }

    /// Most recently observed power state.
    pub fn powered_on(&self) -> bool {
        self.session.powered_on()
    }

    /// Most recently observed volume state, including state sent during setup.
    pub fn volume(&self) -> Option<AndroidTvVolume> {
        self.session.volume()
    }

    /// Active field snapshot; treat it as an expiring write ticket.
    pub fn ime_state(&self) -> Option<AndroidTvImeState> {
        self.session.ime_state()
    }

    /// Set the complete value of the active editor. Success means frame delivery,
    /// not application acceptance; wait for subsequent TV field telemetry.
    pub async fn replace_text(
        &self,
        state: &AndroidTvImeState,
        text: impl Into<String>,
    ) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::IME, "text input")?;
        self.session
            .edit_text(state.clone(), ImeEdit::Replace(text.into()))
            .await
    }
    /// Insert at the current selection (replacing selected text).
    pub async fn append_text(
        &self,
        state: &AndroidTvImeState,
        text: impl Into<String>,
    ) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::IME, "text input")?;
        self.session
            .edit_text(state.clone(), ImeEdit::Append(text.into()))
            .await
    }
    pub async fn delete_backward(&self, state: &AndroidTvImeState) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::IME, "text input")?;
        self.session
            .edit_text(state.clone(), ImeEdit::DeleteBackward)
            .await
    }
    /// Send Enter only while this text field remains active.
    pub async fn submit_text_field(&self, state: &AndroidTvImeState) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::IME, "text input")?;
        self.require_feature(AndroidTvRemoteFeatures::KEY, "key injection")?;
        self.session.edit_text(state.clone(), ImeEdit::Submit).await
    }

    /// Subscribe to subsequent power, volume, text-field and disconnection events.
    pub fn subscribe(&self) -> broadcast::Receiver<AndroidTvRemoteEvent> {
        self.session.subscribe()
    }

    /// Send one key action and wait until its complete frame has been flushed.
    ///
    /// Remote Service does not acknowledge semantic key handling; success means
    /// the ready TLS session accepted the complete protocol frame.
    pub async fn send_key(
        &self,
        key_code: AndroidTvKeyCode,
        action: AndroidTvKeyAction,
    ) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::KEY, "key injection")?;
        self.session.send_key(key_code, action).await
    }

    /// Send one short key press.
    ///
    /// Success confirms frame delivery to the TLS session, not that the device
    /// acted on the key.
    pub async fn press_key(&self, key_code: AndroidTvKeyCode) -> Result<(), AndroidTvError> {
        self.send_key(key_code, AndroidTvKeyAction::Press).await
    }

    /// Inject Android's wake-up key.
    ///
    /// Success confirms frame delivery only; observe [`powered_on`](Self::powered_on)
    /// or events when semantic confirmation is required.
    pub async fn press_wake_key(&self) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::POWER, "power")?;
        self.press_key(AndroidTvKeyCode::WAKEUP).await
    }

    /// Inject Android's sleep key, with frame-delivery rather than semantic
    /// success semantics.
    pub async fn press_sleep_key(&self) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::POWER, "power")?;
        self.press_key(AndroidTvKeyCode::SLEEP).await
    }

    /// Inject Android's power-toggle key, with frame-delivery rather than
    /// semantic success semantics.
    pub async fn press_power_key(&self) -> Result<(), AndroidTvError> {
        self.require_feature(AndroidTvRemoteFeatures::POWER, "power")?;
        self.press_key(AndroidTvKeyCode::POWER).await
    }

    /// Shut down the TLS stream and wait for the owning task to finish.
    pub async fn close(self) -> Result<(), AndroidTvError> {
        self.session.close().await
    }

    fn require_feature(
        &self,
        feature: AndroidTvRemoteFeatures,
        name: &'static str,
    ) -> Result<(), AndroidTvError> {
        if self.features.contains(feature) {
            Ok(())
        } else {
            Err(AndroidTvError::UnsupportedFeature(name))
        }
    }
}
