//! Payload-free diagnostics for local setup; no transport error is formatted.
use super::AndroidTvError;
use rustls::{CertificateError, Error, PeerIncompatible};

impl AndroidTvError {
    /// Fixed categories and TLS alert numbers only. Never returns remote text,
    /// certificate subjects, keys, entered text, or arbitrary error messages.
    pub fn diagnostic_codes(&self) -> Vec<String> {
        let io = match self {
            Self::Io(error) | Self::TcpConnection(error) | Self::TlsHandshake(error) => error,
            Self::RemoteHandshake { source, .. } => return source.diagnostic_codes(),
            _ => return vec![],
        };
        // ErrorKind is a standard, payload-free enum (including unknown variants).
        let mut labels = vec![format!("io_{:?}", io.kind())];
        let Some(error) = io.get_ref().and_then(|e| e.downcast_ref::<Error>()) else {
            labels.push("tls_no_rustls_detail".into());
            return labels;
        };
        let label = match error {
            Error::AlertReceived(alert) => {
                labels.push(format!("tls_alert_{}", u8::from(*alert)));
                return labels;
            }
            Error::InvalidCertificate(cert) => certificate_code(cert),
            Error::PeerIncompatible(reason) => incompatible_code(reason),
            Error::PeerMisbehaved(_) => "tls_peer_misbehaved",
            Error::InappropriateMessage { .. } => "tls_inappropriate_message",
            Error::InappropriateHandshakeMessage { .. } => "tls_inappropriate_handshake",
            Error::InvalidEncryptedClientHello(_) => "tls_invalid_encrypted_hello",
            Error::InvalidMessage(_) => "tls_invalid_message",
            Error::NoCertificatesPresented => "tls_no_certificate",
            Error::UnsupportedNameType => "tls_unsupported_name",
            Error::DecryptError => "tls_decrypt_error",
            Error::EncryptError => "tls_encrypt_error",
            Error::InvalidCertRevocationList(_) => "tls_invalid_revocation_list",
            Error::General(_) => "tls_general",
            Error::FailedToGetCurrentTime => "tls_system_clock",
            Error::FailedToGetRandomBytes => "tls_random_source",
            Error::HandshakeNotComplete => "tls_handshake_incomplete",
            Error::PeerSentOversizedRecord => "tls_oversized_record",
            Error::NoApplicationProtocol => "tls_no_application_protocol",
            Error::BadMaxFragmentSize => "tls_bad_fragment_size",
            Error::InconsistentKeys(_) => "tls_inconsistent_keys",
            Error::Other(_) => "tls_other",
            _ => "tls_unknown_error",
        };
        labels.push(label.into());
        labels
    }
}

#[allow(deprecated)]
fn certificate_code(error: &CertificateError) -> &'static str {
    match error {
        CertificateError::BadEncoding => "tls_cert_encoding",
        CertificateError::Expired | CertificateError::ExpiredContext { .. } => "tls_cert_expired",
        CertificateError::NotValidYet | CertificateError::NotValidYetContext { .. } => {
            "tls_cert_not_yet_valid"
        }
        CertificateError::Revoked => "tls_cert_revoked",
        CertificateError::UnhandledCriticalExtension => "tls_cert_critical_extension",
        CertificateError::UnknownIssuer => "tls_cert_unknown_issuer",
        CertificateError::UnknownRevocationStatus => "tls_cert_revocation_unknown",
        CertificateError::ExpiredRevocationList
        | CertificateError::ExpiredRevocationListContext { .. } => "tls_cert_revocation_expired",
        CertificateError::BadSignature => "tls_cert_bad_signature",
        CertificateError::UnsupportedSignatureAlgorithm
        | CertificateError::UnsupportedSignatureAlgorithmContext { .. } => {
            "tls_cert_signature_algorithm"
        }
        CertificateError::UnsupportedSignatureAlgorithmForPublicKeyContext { .. } => {
            "tls_cert_signature_key_algorithm"
        }
        CertificateError::NotValidForName | CertificateError::NotValidForNameContext { .. } => {
            "tls_cert_name"
        }
        CertificateError::InvalidPurpose | CertificateError::InvalidPurposeContext { .. } => {
            "tls_cert_purpose"
        }
        CertificateError::InvalidOcspResponse => "tls_cert_ocsp",
        CertificateError::ApplicationVerificationFailure => "tls_cert_application_verification",
        CertificateError::Other(_) => "tls_cert_other",
        _ => "tls_cert_unknown_error",
    }
}
fn incompatible_code(error: &PeerIncompatible) -> &'static str {
    match error {
        PeerIncompatible::EcPointsExtensionRequired => "tls_ec_points_required",
        PeerIncompatible::ExtendedMasterSecretExtensionRequired => {
            "tls_extended_master_secret_required"
        }
        PeerIncompatible::IncorrectCertificateTypeExtension => "tls_certificate_type_extension",
        PeerIncompatible::KeyShareExtensionRequired => "tls_key_share_required",
        PeerIncompatible::NamedGroupsExtensionRequired => "tls_named_groups_required",
        PeerIncompatible::NoCertificateRequestSignatureSchemesInCommon => {
            "tls_client_signature_schemes"
        }
        PeerIncompatible::NoCipherSuitesInCommon => "tls_no_shared_cipher",
        PeerIncompatible::NoEcPointFormatsInCommon => "tls_no_shared_ec_format",
        PeerIncompatible::NoKxGroupsInCommon => "tls_no_shared_key_exchange",
        PeerIncompatible::NoSignatureSchemesInCommon => "tls_no_shared_signature",
        PeerIncompatible::NullCompressionRequired => "tls_null_compression_required",
        PeerIncompatible::ServerDoesNotSupportTls12Or13 => "tls_server_version_unsupported",
        PeerIncompatible::ServerSentHelloRetryRequestWithUnknownExtension => {
            "tls_unknown_retry_extension"
        }
        PeerIncompatible::ServerTlsVersionIsDisabledByOurConfig => "tls_server_version_disabled",
        PeerIncompatible::SignatureAlgorithmsExtensionRequired => {
            "tls_signature_algorithms_required"
        }
        PeerIncompatible::SupportedVersionsExtensionRequired => "tls_supported_versions_required",
        PeerIncompatible::Tls12NotOffered => "tls12_not_offered",
        PeerIncompatible::Tls12NotOfferedOrEnabled => "tls12_not_offered_or_enabled",
        PeerIncompatible::Tls13RequiredForQuic => "tls13_required_for_quic",
        PeerIncompatible::UncompressedEcPointsRequired => "tls_uncompressed_ec_required",
        PeerIncompatible::UnsolicitedCertificateTypeExtension => "tls_unsolicited_cert_type",
        PeerIncompatible::ServerRejectedEncryptedClientHello(_) => "tls_encrypted_hello_rejected",
        _ => "tls_peer_incompatible",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn typed_tls_reasons_do_not_expose_error_or_certificate_payloads() {
        let cases = [
            (
                Error::AlertReceived(rustls::AlertDescription::BadCertificate),
                "tls_alert_42",
            ),
            (
                Error::AlertReceived(rustls::AlertDescription::Unknown(199)),
                "tls_alert_199",
            ),
            (
                Error::InvalidCertificate(CertificateError::UnknownIssuer),
                "tls_cert_unknown_issuer",
            ),
            (
                Error::InvalidCertificate(CertificateError::NotValidForNameContext {
                    expected: rustls::pki_types::ServerName::try_from("private-input.example")
                        .unwrap(),
                    presented: vec!["PRIVATE_INPUT".into()],
                }),
                "tls_cert_name",
            ),
            (
                Error::PeerIncompatible(
                    PeerIncompatible::NoCertificateRequestSignatureSchemesInCommon,
                ),
                "tls_client_signature_schemes",
            ),
            (Error::General("PRIVATE_INPUT".into()), "tls_general"),
        ];
        for (error, label) in cases {
            let error = AndroidTvError::TlsHandshake(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                error,
            ));
            assert_eq!(error.diagnostic_codes(), vec!["io_InvalidData", label]);
            assert!(!error.diagnostic_codes().join(" ").contains("PRIVATE_INPUT"));
        }
        let plain = AndroidTvError::TlsHandshake(std::io::Error::new(
            std::io::ErrorKind::UnexpectedEof,
            "PRIVATE_INPUT",
        ));
        assert_eq!(
            plain.diagnostic_codes(),
            vec!["io_UnexpectedEof", "tls_no_rustls_detail"]
        );
    }
}
