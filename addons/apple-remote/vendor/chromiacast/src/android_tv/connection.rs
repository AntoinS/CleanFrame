use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::UnixTime;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::ClientConfig;
use rustls::{DigitallySignedStruct, SignatureScheme};
use sha2::{Digest, Sha256};
use tokio::net::TcpStream;
use tokio::time::timeout;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

use crate::tls::SelfSignedCertificateVerifier;

use super::error::AndroidTvError;
use super::identity::AndroidTvRemoteIdentity;

pub(crate) const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
pub(crate) const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(5);

pub(crate) async fn connect(
    address: SocketAddr,
    identity: &AndroidTvRemoteIdentity,
    server_pin: Option<[u8; 32]>,
) -> Result<TlsStream<TcpStream>, AndroidTvError> {
    let tcp = timeout(CONNECT_TIMEOUT, TcpStream::connect(address))
        .await
        .map_err(|_| AndroidTvError::TimedOut("TCP connection"))?
        .map_err(AndroidTvError::TcpConnection)?;
    tcp.set_nodelay(true).map_err(AndroidTvError::Io)?;

    let verifier = Arc::new(PinnedCertificateVerifier {
        inner: SelfSignedCertificateVerifier::new(),
        pin: server_pin,
    });
    let certificate = CertificateDer::from(identity.certificate_der().to_vec());
    let private_key = PrivateKeyDer::from(PrivatePkcs8KeyDer::from(
        identity.private_key_pkcs8_der().to_vec(),
    ));
    let configuration = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_client_auth_cert(vec![certificate], private_key)
        .map_err(|error| {
            AndroidTvError::InvalidIdentity(format!("configure mutual TLS: {error}"))
        })?;
    let connector = TlsConnector::from(Arc::new(configuration));
    let server_name = ServerName::IpAddress(address.ip().into());
    let stream = timeout(CONNECT_TIMEOUT, connector.connect(server_name, tcp))
        .await
        .map_err(|_| AndroidTvError::TimedOut("TLS handshake"))?
        .map_err(tls_error)?;

    let _ = peer_certificate(&stream)?;
    Ok(stream)
}

fn tls_error(error: std::io::Error) -> AndroidTvError {
    // Preserve the verifier's typed failure, never inspect arbitrary error text.
    if matches!(
        error
            .get_ref()
            .and_then(|e| e.downcast_ref::<rustls::Error>()),
        Some(rustls::Error::InvalidCertificate(
            rustls::CertificateError::ApplicationVerificationFailure
        ))
    ) {
        AndroidTvError::CertificatePinMismatch
    } else {
        AndroidTvError::TlsHandshake(error)
    }
}

pub(crate) fn peer_certificate(stream: &TlsStream<TcpStream>) -> Result<&[u8], AndroidTvError> {
    stream
        .get_ref()
        .1
        .peer_certificates()
        .and_then(|certificates| certificates.first())
        .map(|certificate| certificate.as_ref())
        .ok_or_else(|| {
            AndroidTvError::Connection("device did not present a TLS certificate".into())
        })
}

/// Pairing establishes trust via the displayed code. Established sessions always
/// require the certificate fingerprint returned by that successful exchange.
#[derive(Debug)]
struct PinnedCertificateVerifier {
    inner: SelfSignedCertificateVerifier,
    pin: Option<[u8; 32]>,
}
impl ServerCertVerifier for PinnedCertificateVerifier {
    fn verify_server_cert(
        &self,
        cert: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if let Some(pin) = self.pin {
            if certificate_fingerprint(cert.as_ref()) != pin {
                return Err(rustls::Error::InvalidCertificate(
                    rustls::CertificateError::ApplicationVerificationFailure,
                ));
            }
        }
        self.inner
            .verify_server_cert(cert, intermediates, name, ocsp, now)
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls12_signature(message, cert, signature)
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls13_signature(message, cert, signature)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.inner.supported_verify_schemes()
    }
}
pub(crate) fn certificate_fingerprint(der: &[u8]) -> [u8; 32] {
    Sha256::digest(der).into()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn real_tls_handshake_accepts_only_pinned_peer() {
        use rustls::{ClientConfig, ServerConfig};
        use tokio_rustls::TlsAcceptor;
        let rcgen::CertifiedKey { cert, key_pair } =
            rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let pin = certificate_fingerprint(cert.der().as_ref());
        // Exercise the callback through the actual TLS engine, entirely in memory.
        for protocol in [&rustls::version::TLS12, &rustls::version::TLS13] {
            for correct in [true, false] {
                let server = ServerConfig::builder_with_protocol_versions(&[protocol])
                    .with_no_client_auth()
                    .with_single_cert(
                        vec![cert.der().clone()],
                        PrivatePkcs8KeyDer::from(key_pair.serialize_der()).into(),
                    )
                    .unwrap();
                let verifier = Arc::new(PinnedCertificateVerifier {
                    inner: SelfSignedCertificateVerifier::new(),
                    pin: Some(if correct { pin } else { [0; 32] }),
                });
                let client = ClientConfig::builder_with_protocol_versions(&[protocol])
                    .dangerous()
                    .with_custom_certificate_verifier(verifier)
                    .with_no_client_auth();
                let (client_io, server_io) = tokio::io::duplex(16384);
                let connector = TlsConnector::from(Arc::new(client));
                let acceptor = TlsAcceptor::from(Arc::new(server));
                let result = timeout(Duration::from_secs(2), async {
                    tokio::join!(
                        connector.connect(ServerName::try_from("localhost").unwrap(), client_io),
                        acceptor.accept(server_io)
                    )
                })
                .await
                .unwrap();
                assert_eq!(result.0.is_ok(), correct);
                assert_eq!(result.1.is_ok(), correct);
                if !correct {
                    assert!(matches!(
                        tls_error(result.0.unwrap_err()),
                        AndroidTvError::CertificatePinMismatch
                    ));
                }
            }
        }
    }

    #[test]
    fn established_connections_refuse_other_certificates() {
        let cert = CertificateDer::from(b"paired certificate".to_vec());
        let name = ServerName::try_from("localhost").unwrap();
        let verifier = PinnedCertificateVerifier {
            inner: SelfSignedCertificateVerifier::new(),
            pin: Some(certificate_fingerprint(cert.as_ref())),
        };
        assert!(verifier
            .verify_server_cert(
                &cert,
                &[],
                &name,
                &[],
                UnixTime::since_unix_epoch(Duration::ZERO)
            )
            .is_ok());
        let changed = CertificateDer::from(b"different certificate".to_vec());
        assert!(verifier
            .verify_server_cert(
                &changed,
                &[],
                &name,
                &[],
                UnixTime::since_unix_epoch(Duration::ZERO)
            )
            .is_err());
    }
}
