//! Bounded RTI keyed archives. Plist decoding never instantiates archived classes.
use crate::{error::Error, opack::Value, Result};
use plist::{Dictionary, Uid, Value as P};
use std::io::Cursor;
use unicode_segmentation::UnicodeSegmentation;

#[derive(Clone, PartialEq, Eq)]
pub struct KeyboardState {
    pub session_id: String,
    pub text: String,
}
pub struct KeyboardEdit {
    pub session_id: String,
    pub text: String,
    pub submit: bool,
}
const MAX_TEXT: usize = 16384;
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum KeyboardRejection {
    Malformed,
    Encoding,
    BinaryPlistVersion,
    XmlEncoding,
    PlistDecode,
    ArchiveRoot,
    ArchiveObjects,
    ArchiveTop,
    ArchiveReference,
    OperationType,
    TargetMissing,
    TargetType,
    TargetData,
    TargetString,
    UuidMissing,
    TargetUuid,
    OutputMissing,
    AssertionType,
    OutputType,
    InsertionType,
    EmptyOperation,
    Limit,
    UnsupportedArchive,
    StaleSession,
    TextMismatch,
    UnsupportedOperation,
}
type EditResult<T> = std::result::Result<T, KeyboardRejection>;
fn invalid() -> KeyboardRejection {
    KeyboardRejection::Malformed
}
fn at_stage<T>(value: EditResult<T>, stage: KeyboardRejection) -> EditResult<T> {
    value.map_err(|reason| {
        if reason == KeyboardRejection::Malformed {
            stage
        } else {
            reason
        }
    })
}
fn state_invalid() -> Error {
    Error::Protocol("invalid keyboard state".into())
}

fn dict(items: Vec<(&str, P)>) -> P {
    P::Dictionary(
        items
            .into_iter()
            .map(|(k, v)| (k.to_string(), v))
            .collect::<Dictionary>(),
    )
}
fn uid(n: u64) -> P {
    P::Uid(Uid::new(n))
}

pub(crate) fn state_payload(state: Option<&KeyboardState>) -> Result<Value> {
    let Some(state) = state else {
        return Ok(Value::dict(vec![]));
    };
    if state.text.len() > MAX_TEXT {
        return Err(state_invalid());
    }
    let uuid = uuid::Uuid::parse_str(&state.session_id).map_err(|_| state_invalid())?;
    let archive = dict(vec![
        ("$version", P::Integer(100000.into())),
        ("$archiver", P::String("RTIKeyedArchiver".into())),
        (
            "$top",
            dict(vec![("sessionUUID", uid(1)), ("documentState", uid(2))]),
        ),
        (
            "$objects",
            P::Array(vec![
                P::String("$null".into()),
                P::Data(uuid.as_bytes().to_vec()),
                dict(vec![
                    ("docSt", uid(3)),
                    ("docHasText", P::Boolean(!state.text.is_empty())),
                    ("originatedFromSource", P::Boolean(false)),
                    ("updateMask", P::Integer(1.into())),
                    ("$class", uid(5)),
                ]),
                dict(vec![("contextBeforeInput", uid(4)), ("$class", uid(6))]),
                P::String(state.text.clone()),
                dict(vec![
                    ("$classname", P::String("RTIDocumentState".into())),
                    (
                        "$classes",
                        P::Array(vec![
                            P::String("RTIDocumentState".into()),
                            P::String("NSObject".into()),
                        ]),
                    ),
                ]),
                dict(vec![
                    ("$classname", P::String("TIDocumentState".into())),
                    (
                        "$classes",
                        P::Array(vec![
                            P::String("TIDocumentState".into()),
                            P::String("NSObject".into()),
                        ]),
                    ),
                ]),
            ]),
        ),
    ]);
    let mut bytes = Vec::new();
    archive
        .to_writer_binary(&mut bytes)
        .map_err(|_| state_invalid())?;
    // RTIInputSystemSourceSession only applies version-1 payloads. Omitting
    // this Companion envelope key leaves its session UUID nil, so real iOS
    // subsequently generates target-less operations despite a valid archive.
    Ok(Value::dict(vec![
        Value::kv("_tiV", Value::Int(1)),
        Value::kv("_tiD", Value::Bytes(bytes)),
    ]))
}
struct Archive<'a> {
    objects: &'a [P],
}
impl<'a> Archive<'a> {
    fn resolve(&self, mut value: &'a P) -> EditResult<&'a P> {
        for _ in 0..8 {
            if let P::Uid(id) = value {
                value = self
                    .objects
                    .get(
                        usize::try_from(id.get())
                            .map_err(|_| KeyboardRejection::ArchiveReference)?,
                    )
                    .ok_or(KeyboardRejection::ArchiveReference)?;
            } else {
                return Ok(value);
            }
        }
        Err(KeyboardRejection::ArchiveReference)
    }
    fn field(&self, value: &'a P, name: &str) -> EditResult<Option<&'a P>> {
        let value = self.resolve(value)?;
        let d = value.as_dictionary().ok_or_else(invalid)?;
        d.get(name).map(|v| self.resolve(v)).transpose()
    }
}
fn parse_archive(bytes: &[u8]) -> EditResult<P> {
    if bytes.len() > 64 * 1024 {
        return Err(KeyboardRejection::Limit);
    }
    if !bytes.starts_with(b"bplist00") {
        return Err(if bytes.starts_with(b"bplist") {
            KeyboardRejection::BinaryPlistVersion
        } else if bytes.starts_with(b"<?xml") {
            KeyboardRejection::XmlEncoding
        } else {
            KeyboardRejection::Encoding
        });
    }
    // Bound the library event stream BEFORE materialising a Value. A compact
    // binary plist can reference the same container many times (DAG expansion).
    use plist::stream::{BinaryReader, Event};
    let mut events = Vec::new();
    let mut depth = 0usize;
    let mut decoded = 0usize;
    for event in BinaryReader::new(Cursor::new(bytes)) {
        let event = event.map_err(|_| KeyboardRejection::PlistDecode)?;
        match &event {
            Event::StartArray(n) | Event::StartDictionary(n) => {
                depth += 1;
                if depth > 16 || n.is_some_and(|n| n > 256) {
                    return Err(KeyboardRejection::Limit);
                }
            }
            Event::EndCollection => {
                depth = depth.checked_sub(1).ok_or(KeyboardRejection::PlistDecode)?;
            }
            Event::Data(b) => decoded += b.len(),
            Event::String(s) => decoded += s.len(),
            _ => {}
        }
        if events.len() >= 2048 || decoded > 65536 {
            return Err(KeyboardRejection::Limit);
        }
        events.push(Ok(event));
    }
    P::from_events(events).map_err(|_| KeyboardRejection::PlistDecode)
}

fn archived_class<'a>(archive: &Archive<'a>, value: &'a P) -> EditResult<Option<&'a str>> {
    let Some(class) = archive.field(value, "$class")? else {
        return Ok(None);
    };
    Ok(archive.field(class, "$classname")?.and_then(P::as_string))
}
fn supported_output_field(key: &str) -> bool {
    matches!(
        key,
        "$class" | "insertionText" | "deletionCount" | "producedByDeleteInput"
    )
}
fn supported_operation_field(archive: &Archive<'_>, key: &str, value: &P) -> EditResult<bool> {
    Ok(match key {
        "$class" | "targetSessionUUID" | "keyboardOutput" | "textToAssert" => true,
        "isAutoFillTextOperation" | "isExplicitAutoFillInvocation" => {
            archive.resolve(value)?.as_boolean() == Some(false)
        }
        "inputSourceState" => archived_class(archive, value)? == Some("RTIInputSourceState"),
        "multilingualLanguages" => {
            if !matches!(
                archived_class(archive, value)?,
                Some("NSArray" | "NSMutableArray")
            ) {
                false
            } else if let Some(P::Array(languages)) = archive.field(value, "NS.objects")? {
                languages
                    .iter()
                    .all(|v| archive.resolve(v).ok().and_then(P::as_string).is_some())
            } else {
                false
            }
        }
        _ => false,
    })
}
/// Supported wire operations are clear/assert, append and backward grapheme
/// deletion. Native keyboard/language metadata carries no editing authority.
/// Unknown editing operations fail closed; device validation gates release.
pub(crate) fn decode_edit(bytes: &[u8], current: &KeyboardState) -> EditResult<KeyboardEdit> {
    if current.text.len() > MAX_TEXT {
        return Err(KeyboardRejection::Limit);
    }
    let value = parse_archive(bytes)?;
    let root = value
        .as_dictionary()
        .ok_or(KeyboardRejection::ArchiveRoot)?;
    let objects = root
        .get("$objects")
        .and_then(P::as_array)
        .ok_or(KeyboardRejection::ArchiveObjects)?;
    if objects.len() > 256 {
        return Err(KeyboardRejection::Limit);
    }
    let archive = Archive { objects };
    let top = root.get("$top").ok_or(KeyboardRejection::ArchiveTop)?;
    let op = at_stage(
        archive.field(top, "textOperations"),
        KeyboardRejection::ArchiveTop,
    )?
    .ok_or(KeyboardRejection::UnsupportedArchive)?;
    let operation = op.as_dictionary().ok_or(KeyboardRejection::OperationType)?;
    // Native RTI also carries replacement/selection and autofill operations.
    // Never reinterpret these as a plain append to the focused field.
    for (key, value) in operation {
        if !supported_operation_field(&archive, key, value)? {
            return Err(KeyboardRejection::UnsupportedOperation);
        }
    }
    let target = at_stage(
        archive.field(op, "targetSessionUUID"),
        KeyboardRejection::TargetType,
    )?
    .ok_or(KeyboardRejection::TargetMissing)?;
    if target.as_dictionary().is_none() {
        return Err(match target {
            P::Data(_) => KeyboardRejection::TargetData,
            P::String(_) => KeyboardRejection::TargetString,
            _ => KeyboardRejection::TargetType,
        });
    }
    let uuid = at_stage(
        archive.field(target, "NS.uuidbytes"),
        KeyboardRejection::TargetUuid,
    )?
    .ok_or(KeyboardRejection::UuidMissing)?
    .as_data()
    .ok_or(KeyboardRejection::TargetUuid)?;
    if uuid
        != uuid::Uuid::parse_str(&current.session_id)
            .map_err(|_| invalid())?
            .as_bytes()
    {
        return Err(KeyboardRejection::StaleSession);
    }
    let assertion = at_stage(
        archive.field(op, "textToAssert"),
        KeyboardRejection::AssertionType,
    )?
    .map(|v| v.as_string().ok_or(KeyboardRejection::AssertionType))
    .transpose()?;
    let output = at_stage(
        archive.field(op, "keyboardOutput"),
        KeyboardRejection::OutputType,
    )?
    .ok_or(KeyboardRejection::OutputMissing)?;
    // Reject unsupported deltas instead of silently losing text or selection.
    for key in output
        .as_dictionary()
        .ok_or(KeyboardRejection::OutputType)?
        .keys()
    {
        if !supported_output_field(key) {
            return Err(KeyboardRejection::UnsupportedOperation);
        }
    }
    let insertion = at_stage(
        archive.field(output, "insertionText"),
        KeyboardRejection::InsertionType,
    )?
    .map(|v| v.as_string().ok_or(KeyboardRejection::InsertionType))
    .transpose()?;
    let deletion = archive
        .field(output, "deletionCount")?
        .map(|v| {
            v.as_unsigned_integer()
                .ok_or(KeyboardRejection::UnsupportedOperation)
        })
        .transpose()?
        .unwrap_or(0);
    if deletion > MAX_TEXT as u64 {
        return Err(KeyboardRejection::Limit);
    }
    if archive
        .field(output, "producedByDeleteInput")?
        .is_some_and(|v| v.as_boolean().is_none())
    {
        return Err(KeyboardRejection::UnsupportedOperation);
    }
    let metadata = operation.contains_key("inputSourceState")
        || operation.contains_key("multilingualLanguages");
    let mut text = match assertion {
        Some("") => String::new(),
        Some(s) if s == current.text => current.text.clone(),
        Some(_) => return Err(KeyboardRejection::TextMismatch),
        None => current.text.clone(),
    };
    if assertion.is_none() && insertion.is_none() && deletion == 0 && !metadata {
        return Err(KeyboardRejection::EmptyOperation);
    }
    if deletion > 0 {
        // Native RTI counts backward deletions in extended grapheme clusters.
        // UnicodeSegmentation preserves surrogate, combining and ZWJ sequences.
        let keep = text
            .grapheme_indices(true)
            .rev()
            .nth(deletion as usize - 1)
            .map(|(offset, _)| offset)
            .unwrap_or(0);
        text.truncate(keep);
    }
    let submit = matches!(insertion, Some("\n") | Some("\r"));
    if let Some(s) = insertion {
        if !submit {
            text.push_str(s);
        }
    }
    // The host's submit command performs the editor action only; it does not
    // atomically replace text. Do not silently discard a compound mutation.
    if submit && text != current.text {
        return Err(KeyboardRejection::UnsupportedOperation);
    }
    if text.len() > MAX_TEXT {
        return Err(KeyboardRejection::Limit);
    }
    Ok(KeyboardEdit {
        session_id: current.session_id.clone(),
        text,
        submit,
    })
}

/// A diagnostic token constructed exclusively from compile-time protocol names.
/// Fields are private: callers cannot turn entered text into a structure event.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KeyboardStructure {
    profile: u8,
    context: &'static str,
    field: &'static str,
    kind: &'static str,
}
impl KeyboardStructure {
    pub fn label(self) -> String {
        format!(
            "S{}.{}.{}:{}",
            self.profile, self.context, self.field, self.kind
        )
    }
}
/// Distinguish packet forms without retaining packets or entered values.
/// At most eight profiles and 32 tokens per profile per authenticated session.
#[derive(Default)]
pub(crate) struct KeyboardShapeLog {
    profiles: Vec<Vec<KeyboardStructure>>,
    hits: [u8; 8],
    saturated: bool,
}
impl KeyboardShapeLog {
    pub(crate) fn record(&mut self, bytes: &[u8]) -> Vec<KeyboardStructure> {
        let mut shape = diagnostic_shape(bytes);
        if shape.is_empty() {
            return vec![];
        }
        if shape.len() > 31 {
            shape.truncate(31);
            shape.push(KeyboardStructure {
                profile: 0,
                context: "profile",
                field: "fields",
                kind: "truncated",
            });
        }
        let existing = self.profiles.iter().position(|p| p == &shape);
        if existing.is_none() && self.profiles.len() == 8 {
            if self.saturated {
                return vec![];
            }
            self.saturated = true;
            return vec![KeyboardStructure {
                profile: 0,
                context: "profile",
                field: "capacity",
                kind: "reached",
            }];
        }
        let profile = (existing.unwrap_or(self.profiles.len()) + 1) as u8;
        let mut out = vec![KeyboardStructure {
            profile,
            context: "profile",
            field: "selected",
            kind: "known",
        }];
        let index = (profile - 1) as usize;
        self.hits[index] = (self.hits[index] + 1) % 8;
        if existing.is_none() {
            self.profiles.push(shape.clone());
        }
        // Refresh periodically so a burst cannot evict the sole description
        // from the bounded 200-event diagnostic ring.
        if existing.is_none() || self.hits[index] == 0 {
            for mut token in shape {
                token.profile = profile;
                out.push(token);
            }
        }
        out
    }
}
const TOP_FIELDS: &[&str] = &[
    "sessionUUID",
    "textOperations",
    "documentState",
    "documentTraits",
];
const OP_FIELDS: &[&str] = &[
    "targetSessionUUID",
    "keyboardOutput",
    "textToAssert",
    "selectionRangeToAssert",
    "isAutoFillTextOperation",
    "isExplicitAutoFillInvocation",
    "inputSourceState",
    "customInfo",
    "customInfoType",
    "editingActionSelector",
    "intermediateText",
    "styledIntermediateText",
    "multilingualLanguages",
    "attributedPlaceholders",
    "textCheckingAnnotatedString",
    "textCheckingAnnotationRange",
    "textCheckingAnnotationRemovalRange",
    "textCheckingAnnotationToRemove",
    "textCheckingReplacementRange",
    "typeIdentifiers",
    "attachmentDatas",
    "attachmentClasses",
    "attributedInsertionText",
    "fileHandles",
    "imageGlyphs",
    "imageUserInfos",
    "enumeratedInsertionAnimationStyle",
];
const OUTPUT_FIELDS: &[&str] = &[
    "insertionText",
    "deletionCount",
    "forwardDeletionCount",
    "insertionTextAfterSelection",
    "acceptedCandidate",
    "customInfo",
    "handwritingStrokesToDelete",
    "positionOffset",
    "producedByDeleteInput",
    "shortcutConversion",
    "shouldSendCurrentLocation",
    "textToCommit",
    "unmarkIfNecessary",
    "delimitingPrefix",
];
fn shape_type(archive: &Archive<'_>, value: &P) -> &'static str {
    if matches!(value, P::Uid(id) if id.get()==0) {
        return "null";
    }
    match archive.resolve(value) {
        Ok(P::Dictionary(_)) => "dictionary",
        Ok(P::Array(_)) => "array",
        Ok(P::Data(_)) => "data",
        Ok(P::String(_)) => "string",
        Ok(P::Boolean(_)) => "boolean",
        Ok(P::Integer(_)) => "integer",
        Ok(P::Real(_)) => "real",
        Ok(P::Date(_)) => "date",
        _ => "invalid",
    }
}
fn shape_fields(
    out: &mut Vec<KeyboardStructure>,
    archive: &Archive<'_>,
    context: &'static str,
    value: Option<&P>,
    names: &[&'static str],
) {
    let Some(value) = value else {
        return;
    };
    let Ok(P::Dictionary(fields)) = archive.resolve(value) else {
        return;
    };
    if let Ok(Some(class)) = archive.field(value, "$class") {
        if let Ok(Some(name)) = archive.field(class, "$classname") {
            let kind = match name.as_string() {
                Some("RTITextOperations") => "RTITextOperations",
                Some("TIKeyboardOutput") => "TIKeyboardOutput",
                Some("NSUUID") => "NSUUID",
                Some("RTIInputSourceState") => "RTIInputSourceState",
                Some("RTIDocumentState") => "RTIDocumentState",
                Some("TIDocumentState") => "TIDocumentState",
                Some("NSValue") => "NSValue",
                Some("NSDictionary") => "NSDictionary",
                Some("NSMutableDictionary") => "NSMutableDictionary",
                Some("NSArray") => "NSArray",
                Some("NSMutableArray") => "NSMutableArray",
                _ => "other",
            };
            out.push(KeyboardStructure {
                profile: 0,
                context,
                field: "class",
                kind,
            });
        }
    }
    for &field in names {
        if let Some(value) = fields.get(field) {
            out.push(KeyboardStructure {
                profile: 0,
                context,
                field,
                kind: shape_type(archive, value),
            });
        }
    }
    if fields
        .keys()
        .any(|key| key != "$class" && !names.contains(&key.as_str()))
    {
        out.push(KeyboardStructure {
            profile: 0,
            context,
            field: "other-field",
            kind: "present",
        });
    }
}
/// Read-only structural inspection uses the same bounded plist decoder. Neither
/// arbitrary keys/classes nor scalar values, UUIDs, text or lengths escape.
pub(crate) fn diagnostic_shape(bytes: &[u8]) -> Vec<KeyboardStructure> {
    let mut out = vec![];
    let Ok(value) = parse_archive(bytes) else {
        return out;
    };
    let Some(root) = value.as_dictionary() else {
        return out;
    };
    let Some(objects) = root.get("$objects").and_then(P::as_array) else {
        return out;
    };
    let archive = Archive { objects };
    let Some(top) = root.get("$top") else {
        return out;
    };
    shape_fields(&mut out, &archive, "top", Some(top), TOP_FIELDS);
    let op = archive.field(top, "textOperations").ok().flatten();
    shape_fields(&mut out, &archive, "operation", op, OP_FIELDS);
    if let Some(op) = op {
        if let Some(fields) = op.as_dictionary() {
            for (key, value) in fields {
                if supported_operation_field(&archive, key, value) == Ok(false) {
                    let field = OP_FIELDS
                        .iter()
                        .copied()
                        .find(|name| *name == key)
                        .unwrap_or("other-field");
                    out.push(KeyboardStructure {
                        profile: 0,
                        context: "rejected-operation",
                        field,
                        kind: "unsupported",
                    });
                }
            }
        }
        shape_fields(
            &mut out,
            &archive,
            "input-source",
            archive.field(op, "inputSourceState").ok().flatten(),
            &["mask"],
        );
        shape_fields(
            &mut out,
            &archive,
            "languages",
            archive.field(op, "multilingualLanguages").ok().flatten(),
            &["NS.objects"],
        );
        let target = archive.field(op, "targetSessionUUID").ok().flatten();
        shape_fields(&mut out, &archive, "target", target, &["NS.uuidbytes"]);
        let output = archive.field(op, "keyboardOutput").ok().flatten();
        shape_fields(&mut out, &archive, "output", output, OUTPUT_FIELDS);
        if let Some(fields) = output.and_then(P::as_dictionary) {
            for key in fields.keys().filter(|k| !supported_output_field(k)) {
                let field = OUTPUT_FIELDS
                    .iter()
                    .copied()
                    .find(|name| *name == key)
                    .unwrap_or("other-field");
                out.push(KeyboardStructure {
                    profile: 0,
                    context: "rejected-output",
                    field,
                    kind: "unsupported",
                });
            }
        }
    }
    out
}
#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    pub(crate) fn input(id: &str, text: &str, clear: bool) -> Vec<u8> {
        let mut op = vec![
            (
                "targetSessionUUID",
                dict(vec![(
                    "NS.uuidbytes",
                    P::Data(uuid::Uuid::parse_str(id).unwrap().as_bytes().to_vec()),
                )]),
            ),
            (
                "keyboardOutput",
                dict(vec![("insertionText", P::String(text.into()))]),
            ),
        ];
        if clear {
            op.push(("textToAssert", P::String("".into())));
        }
        let v = dict(vec![
            (
                "$objects",
                P::Array(vec![P::String("$null".into()), dict(op)]),
            ),
            ("$top", dict(vec![("textOperations", uid(1))])),
        ]);
        let mut bytes = vec![];
        v.to_writer_binary(&mut bytes).unwrap();
        bytes
    }
    #[test]
    fn unicode_replace_stale_and_submit() {
        let current = KeyboardState {
            session_id: uuid::Uuid::new_v4().to_string(),
            text: "old".into(),
        };
        assert_eq!(
            decode_edit(&input(&current.session_id, "ä🐈", true), &current)
                .unwrap()
                .text,
            "ä🐈"
        );
        assert_eq!(
            decode_edit(&input(&current.session_id, "!", false), &current)
                .unwrap()
                .text,
            "old!"
        );
        assert!(decode_edit(
            &input(&uuid::Uuid::new_v4().to_string(), "!", false),
            &current
        )
        .is_err());
        assert!(
            decode_edit(&input(&current.session_id, "\n", false), &current)
                .unwrap()
                .submit
        );
        assert!(decode_edit(b"garbage", &current).is_err());
    }
    #[test]
    fn rejects_deep_binary_plist_before_building_tree() {
        let current = KeyboardState {
            session_id: uuid::Uuid::new_v4().to_string(),
            text: String::new(),
        };
        let mut value = P::String("leaf".into());
        for _ in 0..20 {
            value = P::Array(vec![value]);
        }
        let mut bytes = vec![];
        value.to_writer_binary(&mut bytes).unwrap();
        assert!(decode_edit(&bytes, &current).is_err());
    }
    #[test]
    fn rejection_reasons_identify_shape_without_exposing_text() {
        let current = KeyboardState {
            session_id: uuid::Uuid::new_v4().to_string(),
            text: "PRIVATE".into(),
        };
        assert!(matches!(
            decode_edit(
                &input(&uuid::Uuid::new_v4().to_string(), "SECRET", false),
                &current
            ),
            Err(KeyboardRejection::StaleSession)
        ));
        let base = input(&current.session_id, "SECRET", false);
        for (reason, kind) in [
            (KeyboardRejection::UnsupportedOperation, 0),
            (KeyboardRejection::TextMismatch, 1),
            (KeyboardRejection::UnsupportedArchive, 2),
        ] {
            let mut value = P::from_reader(Cursor::new(&base)).unwrap();
            let root = value.as_dictionary_mut().unwrap();
            if kind == 2 {
                root.get_mut("$top")
                    .unwrap()
                    .as_dictionary_mut()
                    .unwrap()
                    .remove("textOperations");
            } else {
                let op = root.get_mut("$objects").unwrap().as_array_mut().unwrap()[1]
                    .as_dictionary_mut()
                    .unwrap();
                if kind == 0 {
                    op.get_mut("keyboardOutput")
                        .unwrap()
                        .as_dictionary_mut()
                        .unwrap()
                        .insert("forwardDeletionCount".into(), P::Integer(1.into()));
                } else {
                    op.insert("textToAssert".into(), P::String("OTHER_PRIVATE".into()));
                }
            }
            let mut bytes = vec![];
            value.to_writer_binary(&mut bytes).unwrap();
            assert!(matches!(decode_edit(&bytes,&current),Err(found) if found==reason));
        }
    }
    #[test]
    fn structural_profiles_are_bounded_and_never_contain_values_or_unknown_names() {
        let id = "11111111-2222-4333-8444-555555555555";
        let mut value = P::from_reader(Cursor::new(input(id, "SECRET_CONTENT", false))).unwrap();
        let root = value.as_dictionary_mut().unwrap();
        let op = root.get_mut("$objects").unwrap().as_array_mut().unwrap()[1]
            .as_dictionary_mut()
            .unwrap();
        op.insert("SECRET_FIELD_NAME".into(), P::String("SECRET_VALUE".into()));
        op.insert(
            "$class".into(),
            dict(vec![("$classname", P::String("SECRET_CLASS".into()))]),
        );
        let mut bytes = vec![];
        value.to_writer_binary(&mut bytes).unwrap();
        let mut log = KeyboardShapeLog::default();
        let first = log.record(&bytes);
        assert!(first.len() > 1);
        for token in &first {
            let label = token.label();
            assert!(label.len() <= 100);
            assert!(label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b)));
            assert!(!label.contains("SECRET"));
            assert!(!label.contains(id));
        }
        assert!(first
            .iter()
            .any(|t| t.label() == "S1.operation.other-field:present"));
        assert!(first
            .iter()
            .any(|t| t.label() == "S1.operation.class:other"));
        for _ in 0..6 {
            assert_eq!(log.record(&bytes).len(), 1);
        }
        assert_eq!(log.record(&bytes), first);
        // Distinct field types make distinct profiles; entered string values do not.
        let unchanged_shape = input(id, "ANOTHER_SECRET", false);
        let mut clean = KeyboardShapeLog::default();
        clean.record(&input(id, "ONE", false));
        assert_eq!(clean.record(&unchanged_shape).len(), 1);
        for field in OP_FIELDS.iter().take(12) {
            let mut v = P::from_reader(Cursor::new(&bytes)).unwrap();
            v.as_dictionary_mut()
                .unwrap()
                .get_mut("$objects")
                .unwrap()
                .as_array_mut()
                .unwrap()[1]
                .as_dictionary_mut()
                .unwrap()
                .insert((*field).into(), P::Boolean(false));
            let mut changed = vec![];
            v.to_writer_binary(&mut changed).unwrap();
            assert!(log.record(&changed).len() <= 33);
        }
        assert_eq!(log.profiles.len(), 8);
        assert!(log.saturated);
        assert!(log.profiles.iter().all(|p| p.len() <= 32));
    }
    #[test]
    fn native_envelope_uuid_is_not_silently_substituted_for_missing_edit_target() {
        let bytes = include_bytes!("../tests/fixtures/tvos27-synthetic-untargeted.bplist");
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: String::new(),
        };
        assert_eq!(
            decode_edit(bytes, &current).err(),
            Some(KeyboardRejection::TargetMissing)
        );
        let labels: Vec<_> = diagnostic_shape(bytes).iter().map(|s| s.label()).collect();
        assert!(labels.contains(&"S0.top.sessionUUID:data".into()));
        assert!(labels.contains(&"S0.operation.class:RTITextOperations".into()));
        assert!(!labels.iter().any(|s| s.contains("targetSessionUUID")));
        let metadata = include_bytes!("../tests/fixtures/tvos27-synthetic-input-source.bplist");
        assert_eq!(decode_edit(metadata, &current).unwrap().text, current.text);
        assert!(diagnostic_shape(metadata)
            .iter()
            .any(|s| s.label() == "S0.operation.inputSourceState:dictionary"));
        assert!(!diagnostic_shape(metadata)
            .iter()
            .any(|s| s.label().contains("rejected-operation")));
    }
    #[test]
    fn bounded_diagnostic_stages_do_not_include_archive_values() {
        use KeyboardRejection::*;
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: "PRIVATE".into(),
        };
        for (bytes, reason) in [
            (&b"bplist16PRIVATE"[..], BinaryPlistVersion),
            (&b"<?xml PRIVATE"[..], XmlEncoding),
            (&b"OTHER_PRIVATE"[..], Encoding),
            (&b"bplist00PRIVATE"[..], PlistDecode),
        ] {
            assert_eq!(decode_edit(bytes, &current).err(), Some(reason));
        }
        macro_rules! reject {
            ($reason:ident, $value:ident, $change:block) => {{
                let mut $value = P::from_reader(Cursor::new(input(&current.session_id, "SECRET", false))).unwrap();
                $change
                let mut bytes = vec![];
                $value.to_writer_binary(&mut bytes).unwrap();
                let reason = decode_edit(&bytes, &current).err().unwrap();
                assert_eq!(reason, $reason);
                assert!(!format!("{reason:?}").contains("PRIVATE"));
                assert!(!format!("{reason:?}").contains("SECRET"));
            }};
        }
        fn op(v: &mut P) -> &mut Dictionary {
            v.as_dictionary_mut()
                .unwrap()
                .get_mut("$objects")
                .unwrap()
                .as_array_mut()
                .unwrap()[1]
                .as_dictionary_mut()
                .unwrap()
        }
        reject!(ArchiveRoot, v, {
            assert!(v.as_dictionary().is_some());
            v = P::Boolean(false);
        });
        reject!(ArchiveObjects, v, {
            v.as_dictionary_mut().unwrap().remove("$objects");
        });
        reject!(ArchiveTop, v, {
            v.as_dictionary_mut().unwrap().remove("$top");
        });
        reject!(ArchiveReference, v, {
            v.as_dictionary_mut()
                .unwrap()
                .insert("$top".into(), uid(999));
        });
        reject!(OperationType, v, {
            v.as_dictionary_mut()
                .unwrap()
                .get_mut("$objects")
                .unwrap()
                .as_array_mut()
                .unwrap()[1] = P::Boolean(false);
        });
        reject!(TargetMissing, v, {
            op(&mut v).remove("targetSessionUUID");
        });
        reject!(TargetData, v, {
            op(&mut v).insert("targetSessionUUID".into(), P::Data(vec![0; 16]));
        });
        reject!(TargetString, v, {
            op(&mut v).insert("targetSessionUUID".into(), P::String("PRIVATE".into()));
        });
        reject!(TargetType, v, {
            op(&mut v).insert("targetSessionUUID".into(), P::Boolean(false));
        });
        reject!(UuidMissing, v, {
            op(&mut v).insert("targetSessionUUID".into(), dict(vec![]));
        });
        reject!(TargetUuid, v, {
            op(&mut v).insert(
                "targetSessionUUID".into(),
                dict(vec![("NS.uuidbytes", P::Boolean(false))]),
            );
        });
        reject!(AssertionType, v, {
            op(&mut v).insert("textToAssert".into(), P::Boolean(false));
        });
        reject!(OutputMissing, v, {
            op(&mut v).remove("keyboardOutput");
        });
        reject!(OutputType, v, {
            op(&mut v).insert("keyboardOutput".into(), P::Boolean(false));
        });
        reject!(InsertionType, v, {
            op(&mut v).insert(
                "keyboardOutput".into(),
                dict(vec![("insertionText", P::Boolean(false))]),
            );
        });
        reject!(EmptyOperation, v, {
            op(&mut v).insert("keyboardOutput".into(), dict(vec![]));
        });
        reject!(UnsupportedOperation, v, {
            op(&mut v).insert("selectionRangeToAssert".into(), P::String("PRIVATE".into()));
        });
        reject!(UnsupportedOperation, v, {
            op(&mut v).insert("isAutoFillTextOperation".into(), P::Boolean(true));
        });
    }
    #[test]
    fn native_replacement_must_not_be_reinterpreted_as_append() {
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: "Probe".into(),
        };
        assert_eq!(
            decode_edit(
                include_bytes!("../tests/fixtures/tvos27-synthetic-replace.bplist"),
                &current
            )
            .err(),
            Some(KeyboardRejection::UnsupportedOperation)
        );
    }
    #[test]
    fn apple_tvos27_generated_insert_uses_supported_schema() {
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: String::new(),
        };
        let edit = decode_edit(
            include_bytes!("../tests/fixtures/tvos27-synthetic-insert.bplist"),
            &current,
        )
        .unwrap();
        assert_eq!(edit.session_id, current.session_id);
        assert_eq!(edit.text, "ä🎵");
        assert!(!edit.submit);
    }
    fn native_output_mut(value: &mut P) -> &mut Dictionary {
        let root = value.as_dictionary().unwrap();
        let objects = root["$objects"].as_array().unwrap();
        let index = root["$top"].as_dictionary().unwrap()["textOperations"]
            .as_uid()
            .unwrap()
            .get() as usize;
        let output = objects[index].as_dictionary().unwrap()["keyboardOutput"]
            .as_uid()
            .unwrap()
            .get() as usize;
        value
            .as_dictionary_mut()
            .unwrap()
            .get_mut("$objects")
            .unwrap()
            .as_array_mut()
            .unwrap()[output]
            .as_dictionary_mut()
            .unwrap()
    }
    fn encode(value: &P) -> Vec<u8> {
        let mut bytes = vec![];
        value.to_writer_binary(&mut bytes).unwrap();
        bytes
    }
    #[test]
    fn native_language_metadata_does_not_block_targeted_text_or_create_text() {
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: "Probe".into(),
        };
        let meta = include_bytes!("../tests/fixtures/tvos27-synthetic-metadata.bplist");
        let insert = include_bytes!("../tests/fixtures/tvos27-synthetic-insert-metadata.bplist");
        assert_eq!(decode_edit(meta, &current).unwrap().text, "Probe");
        assert_eq!(decode_edit(insert, &current).unwrap().text, "Probeä🎵");
        for field in ["inputSourceState", "multilingualLanguages"] {
            for bad in [
                P::Boolean(true),
                dict(vec![]),
                dict(vec![(
                    "$class",
                    dict(vec![("$classname", P::String("UnapprovedClass".into()))]),
                )]),
            ] {
                let mut value = P::from_reader(Cursor::new(insert)).unwrap();
                let root = value.as_dictionary_mut().unwrap();
                let index = root["$top"].as_dictionary().unwrap()["textOperations"]
                    .as_uid()
                    .unwrap()
                    .get() as usize;
                root.get_mut("$objects").unwrap().as_array_mut().unwrap()[index]
                    .as_dictionary_mut()
                    .unwrap()
                    .insert(field.into(), bad);
                assert!(decode_edit(&encode(&value), &current).is_err());
            }
        }
        let mut switched = current.clone();
        switched.session_id = uuid::Uuid::new_v4().to_string();
        assert_eq!(
            decode_edit(meta, &switched).err(),
            Some(KeyboardRejection::StaleSession)
        );
        assert_eq!(
            decode_edit(insert, &switched).err(),
            Some(KeyboardRejection::StaleSession)
        );
        assert!(diagnostic_shape(insert)
            .iter()
            .all(|s| !s.label().contains("rejected")));
    }
    #[test]
    fn observed_mutable_language_array_keeps_content_and_target_validation() {
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: "Probe".into(),
        };
        let fixture = include_bytes!("../tests/fixtures/tvos27-synthetic-insert-metadata.bplist");
        let mut value = P::from_reader(Cursor::new(fixture)).unwrap();
        let objects = value
            .as_dictionary_mut()
            .unwrap()
            .get_mut("$objects")
            .unwrap()
            .as_array_mut()
            .unwrap();
        let mut changed = false;
        for object in objects.iter_mut() {
            if let Some(class) = object.as_dictionary_mut() {
                if class.get("$classname").and_then(P::as_string) == Some("NSArray") {
                    class.insert("$classname".into(), P::String("NSMutableArray".into()));
                    class.insert(
                        "$classes".into(),
                        P::Array(vec![
                            P::String("NSMutableArray".into()),
                            P::String("NSArray".into()),
                            P::String("NSObject".into()),
                        ]),
                    );
                    changed = true;
                }
            }
        }
        assert!(changed);
        assert_eq!(
            decode_edit(&encode(&value), &current).unwrap().text,
            "Probeä🎵"
        );
        let mut stale = current.clone();
        stale.session_id = uuid::Uuid::new_v4().to_string();
        assert_eq!(
            decode_edit(&encode(&value), &stale).err(),
            Some(KeyboardRejection::StaleSession)
        );
        let root = value.as_dictionary_mut().unwrap();
        let objects = root.get_mut("$objects").unwrap().as_array_mut().unwrap();
        for object in objects.iter_mut() {
            if let Some(dictionary) = object.as_dictionary_mut() {
                if dictionary.contains_key("NS.objects") {
                    dictionary.insert("NS.objects".into(), P::Array(vec![P::Boolean(true)]));
                }
            }
        }
        assert_eq!(
            decode_edit(&encode(&value), &current).err(),
            Some(KeyboardRejection::UnsupportedOperation)
        );
    }
    #[test]
    fn native_backward_delete_uses_graphemes_then_inserts_and_clamps_at_empty() {
        let fixture = include_bytes!("../tests/fixtures/tvos27-synthetic-delete-metadata.bplist");
        for (text, one) in [
            ("abc", "ab"),
            ("aä", "a"),
            ("a🎵", "a"),
            ("ae\u{301}", "a"),
            ("a👩‍👩‍👧‍👦", "a"),
            ("a🇩🇪", "a"),
            ("a👍🏽", "a"),
            ("", ""),
        ] {
            let current = KeyboardState {
                session_id: "11111111-2222-4333-8444-555555555555".into(),
                text: text.into(),
            };
            for count in 1..=3u64 {
                for insert in [false, true] {
                    let mut value = P::from_reader(Cursor::new(fixture)).unwrap();
                    let output = native_output_mut(&mut value);
                    output.insert("deletionCount".into(), P::Integer(count.into()));
                    if insert {
                        output.insert("insertionText".into(), P::String("Z".into()));
                    }
                    let mut expected = if count == 1 {
                        one
                    } else if text == "abc" && count == 2 {
                        "a"
                    } else {
                        ""
                    }
                    .to_string();
                    if insert {
                        expected.push('Z');
                    }
                    assert_eq!(
                        decode_edit(&encode(&value), &current).unwrap().text,
                        expected
                    );
                }
            }
        }
    }
    #[test]
    fn submit_cannot_silently_drop_combined_delete_or_clear() {
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: "Probe".into(),
        };
        for newline in ["\n", "\r"] {
            let mut value = P::from_reader(Cursor::new(include_bytes!(
                "../tests/fixtures/tvos27-synthetic-delete-metadata.bplist"
            )))
            .unwrap();
            native_output_mut(&mut value).insert("insertionText".into(), P::String(newline.into()));
            assert_eq!(
                decode_edit(&encode(&value), &current).err(),
                Some(KeyboardRejection::UnsupportedOperation)
            );
            assert_eq!(
                decode_edit(&input(&current.session_id, newline, true), &current).err(),
                Some(KeyboardRejection::UnsupportedOperation)
            );
            let plain = decode_edit(&input(&current.session_id, newline, false), &current).unwrap();
            assert!(plain.submit);
            assert_eq!(plain.text, current.text);
            let empty = KeyboardState {
                session_id: current.session_id.clone(),
                text: String::new(),
            };
            assert!(
                decode_edit(&input(&empty.session_id, newline, true), &empty)
                    .unwrap()
                    .submit
            );
        }
    }
    #[test]
    fn deletion_requires_bounded_integer_and_boolean_flag_and_keeps_unknown_deltas_rejected() {
        let fixture = include_bytes!("../tests/fixtures/tvos27-synthetic-delete-metadata.bplist");
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: "Probe".into(),
        };
        for (field, bad) in [
            ("deletionCount", P::Integer((-1i64).into())),
            ("deletionCount", P::Integer(u64::MAX.into())),
            ("deletionCount", P::String("1".into())),
            ("producedByDeleteInput", P::String("true".into())),
            ("forwardDeletionCount", P::Integer(1.into())),
            ("positionOffset", P::Integer(1.into())),
        ] {
            let mut value = P::from_reader(Cursor::new(fixture)).unwrap();
            native_output_mut(&mut value).insert(field.into(), bad);
            assert!(decode_edit(&encode(&value), &current).is_err());
        }
    }
    #[test]
    fn native_source_session_creates_targeted_input_only_after_version_one_state() {
        let current = KeyboardState {
            session_id: "11111111-2222-4333-8444-555555555555".into(),
            text: "Probe".into(),
        };
        let version_zero =
            include_bytes!("../tests/fixtures/tvos27-source-version-zero-edit.bplist");
        let version_one = include_bytes!("../tests/fixtures/tvos27-source-version-one-edit.bplist");
        assert_eq!(
            decode_edit(version_zero, &current).err(),
            Some(KeyboardRejection::TargetMissing)
        );
        let accepted = decode_edit(version_one, &current).unwrap();
        assert_eq!(accepted.session_id, current.session_id);
        assert_eq!(accepted.text, "Probeä🎵");
        let mut switched = current;
        switched.session_id = uuid::Uuid::new_v4().to_string();
        assert_eq!(
            decode_edit(version_one, &switched).err(),
            Some(KeyboardRejection::StaleSession)
        );
    }
    #[test]
    fn state_archive_carries_native_document_classes_and_raw_session_uuid() {
        for text in ["", "Probe"] {
            let current = KeyboardState {
                session_id: "11111111-2222-4333-8444-555555555555".into(),
                text: text.into(),
            };
            let payload = state_payload(Some(&current)).unwrap();
            assert_eq!(payload.get("_tiV").and_then(Value::as_int), Some(1));
            let bytes = payload.get("_tiD").unwrap().as_bytes().unwrap();
            let generated = P::from_reader(Cursor::new(bytes)).unwrap();
            let native = P::from_reader(Cursor::new(include_bytes!(
                "../tests/fixtures/tvos27-synthetic-state.bplist"
            )))
            .unwrap();
            for archive in [&generated, &native] {
                let root = archive.as_dictionary().unwrap();
                let resolver = Archive {
                    objects: root["$objects"].as_array().unwrap(),
                };
                let top = &root["$top"];
                let document = resolver.field(top, "documentState").unwrap().unwrap();
                let document_class = resolver.field(document, "$class").unwrap().unwrap();
                assert_eq!(
                    resolver
                        .field(document_class, "$classname")
                        .unwrap()
                        .unwrap()
                        .as_string(),
                    Some("RTIDocumentState")
                );
                let inner = resolver.field(document, "docSt").unwrap().unwrap();
                let inner_class = resolver.field(inner, "$class").unwrap().unwrap();
                assert_eq!(
                    resolver
                        .field(inner_class, "$classname")
                        .unwrap()
                        .unwrap()
                        .as_string(),
                    Some("TIDocumentState")
                );
                assert_eq!(
                    resolver
                        .field(top, "sessionUUID")
                        .unwrap()
                        .unwrap()
                        .as_data()
                        .unwrap(),
                    uuid::Uuid::parse_str(&current.session_id)
                        .unwrap()
                        .as_bytes()
                );
            }
            let root = generated.as_dictionary().unwrap();
            let resolver = Archive {
                objects: root["$objects"].as_array().unwrap(),
            };
            let document = resolver
                .field(&root["$top"], "documentState")
                .unwrap()
                .unwrap();
            assert_eq!(
                resolver
                    .field(document, "docHasText")
                    .unwrap()
                    .unwrap()
                    .as_boolean(),
                Some(!text.is_empty())
            );
        }
    }
}
