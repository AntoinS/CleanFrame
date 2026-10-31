use super::error::{protocol_error, AndroidTvError};
use super::proto::{
    RemoteEditInfo, RemoteImeBatchEdit, RemoteImeObject, RemoteKeyInject, RemoteMessage,
    RemoteTextFieldStatus,
};
use super::remote_types::{AndroidTvImeState, AndroidTvKeyAction, AndroidTvKeyCode};

pub(super) const MAX_TEXT_UTF16: usize = 8192;

pub(super) enum ImeEdit {
    Replace(String),
    Append(String),
    DeleteBackward,
    Submit,
}

pub(super) struct ImeTracker {
    session_id: u64,
    revision: u64,
    generation: u64,
    field: Option<RemoteTextFieldStatus>,
    counters: Option<(i32, i32)>,
    app: String,
}
impl ImeTracker {
    pub fn new() -> Self {
        Self {
            session_id: rand::random(),
            revision: 0,
            generation: 0,
            field: None,
            counters: None,
            app: String::new(),
        }
    }
    pub fn state(&self) -> Option<AndroidTvImeState> {
        let field = self.field.as_ref()?;
        let (ime_counter, field_counter) = self.counters?;
        if field.counter_field != field_counter {
            return None;
        }
        Some(AndroidTvImeState {
            session_id: self.session_id,
            revision: self.revision,
            field_generation: self.generation,
            ime_counter,
            field_counter,
            text: field.value.clone(),
            selection_start: field.start,
            selection_end: field.end,
            label: field.label.clone(),
        })
    }
    pub fn clear(&mut self) {
        self.field = None;
        self.counters = None;
        self.generation = self.generation.wrapping_add(1);
        self.revision = self.revision.wrapping_add(1);
    }
    pub fn receive(&mut self, message: &mut RemoteMessage) -> Result<bool, AndroidTvError> {
        let mut changed = false;
        if let Some(inject) = message.ime_key_inject.take() {
            changed = true;
            if let Some(app) = inject.app_info {
                if app.app_package.len() > 256 {
                    return Err(protocol_error("IME app name too long"));
                }
                if self.app != app.app_package {
                    self.clear();
                    self.app = app.app_package;
                }
            }
            self.set_field(inject.text_field_status)?;
        }
        if let Some(show) = message.ime_show_request.take() {
            changed = true;
            self.set_field(show.text_field_status)?;
        }
        if let Some(batch) = message.ime_batch_edit.take() {
            changed = true;
            if batch.ime_counter < 0 || batch.field_counter < 0 || batch.edit_info.len() > 64 {
                return Err(protocol_error("invalid IME counters or batch"));
            }
            self.counters = Some((batch.ime_counter, batch.field_counter));
        }
        if changed {
            self.revision = self.revision.wrapping_add(1);
        }
        Ok(changed)
    }
    fn set_field(&mut self, field: Option<RemoteTextFieldStatus>) -> Result<(), AndroidTvError> {
        if let Some(ref value) = field {
            validate_text(&value.value)?;
            if value.counter_field < 0
                || value.label.len() > 256
                || !valid_offset(&value.value, value.start)
                || !valid_offset(&value.value, value.end)
            {
                // Android uses -1 selections for an editor without an active
                // text connection. Do not manufacture a writable empty field.
                self.field = None;
                self.generation = self.generation.wrapping_add(1);
                return Ok(());
            }
        }
        if self.field.as_ref().map(|f| f.counter_field) != field.as_ref().map(|f| f.counter_field) {
            self.generation = self.generation.wrapping_add(1);
        }
        self.field = field;
        Ok(())
    }
    pub fn prepare(
        &self,
        ticket: &AndroidTvImeState,
        edit: &ImeEdit,
    ) -> Result<(RemoteMessage, Option<RemoteTextFieldStatus>), AndroidTvError> {
        let current = self.state().ok_or(AndroidTvError::StaleTextField)?;
        if ticket.session_id != current.session_id
            || ticket.field_generation != current.field_generation
            || ticket.revision != current.revision
            || ticket.ime_counter != current.ime_counter
            || ticket.field_counter != current.field_counter
        {
            return Err(AndroidTvError::StaleTextField);
        }
        if matches!(edit, ImeEdit::Submit) {
            return Ok((
                RemoteMessage {
                    key_inject: Some(RemoteKeyInject {
                        key_code: AndroidTvKeyCode::ENTER.0,
                        direction: AndroidTvKeyAction::Press.protocol_value(),
                    }),
                    ..Default::default()
                },
                None,
            ));
        }
        let mut field = self.field.clone().ok_or(AndroidTvError::StaleTextField)?;
        let (mut start, mut end) = (field.start.min(field.end), field.start.max(field.end));
        let replacement = match edit {
            ImeEdit::Replace(value) => {
                start = 0;
                end = field.value.encode_utf16().count() as i32;
                value.clone()
            }
            ImeEdit::Append(value) => value.clone(),
            ImeEdit::DeleteBackward => {
                if start == end && start > 0 {
                    let prefix = &field.value[..utf16_byte_index(&field.value, start).unwrap()];
                    start -= prefix.chars().next_back().unwrap().len_utf16() as i32;
                }
                String::new()
            }
            ImeEdit::Submit => unreachable!(),
        };
        validate_text(&replacement)?;
        let a = utf16_byte_index(&field.value, start).ok_or(AndroidTvError::StaleTextField)?;
        let b = utf16_byte_index(&field.value, end).ok_or(AndroidTvError::StaleTextField)?;
        field.value.replace_range(a..b, &replacement);
        validate_text(&field.value)?;
        field.start = start + replacement.encode_utf16().count() as i32;
        field.end = field.start;
        // Full-field value update (insert=0), with the resulting selection.
        // Field and IME counters are always from the active acknowledged state.
        let message = RemoteMessage {
            ime_batch_edit: Some(RemoteImeBatchEdit {
                ime_counter: current.ime_counter,
                field_counter: current.field_counter,
                edit_info: vec![RemoteEditInfo {
                    insert: 0,
                    text_field_status: Some(RemoteImeObject {
                        start: field.start,
                        end: field.end,
                        value: field.value.clone(),
                    }),
                }],
            }),
            ..Default::default()
        };
        Ok((message, Some(field)))
    }
    pub fn commit_sent(&mut self, field: Option<RemoteTextFieldStatus>) {
        if let Some(field) = field {
            self.field = Some(field);
            self.revision = self.revision.wrapping_add(1);
        }
    }
}
fn validate_text(value: &str) -> Result<(), AndroidTvError> {
    if value.encode_utf16().count() > MAX_TEXT_UTF16 || value.contains('\0') {
        Err(protocol_error("IME text exceeds limit or contains NUL"))
    } else {
        Ok(())
    }
}
fn valid_offset(text: &str, offset: i32) -> bool {
    utf16_byte_index(text, offset).is_some()
}
fn utf16_byte_index(text: &str, offset: i32) -> Option<usize> {
    if offset < 0 {
        return None;
    }
    let mut units = 0;
    for (i, c) in text.char_indices() {
        if units == offset {
            return Some(i);
        }
        units += c.len_utf16() as i32;
    }
    if units == offset {
        Some(text.len())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::super::proto::RemoteImeShowRequest;
    use super::*;
    fn ready() -> ImeTracker {
        let mut tracker = ImeTracker::new();
        tracker
            .receive(&mut RemoteMessage {
                ime_show_request: Some(RemoteImeShowRequest {
                    text_field_status: Some(RemoteTextFieldStatus {
                        counter_field: 9,
                        value: "Grüße 🎵".into(),
                        start: 8,
                        end: 8,
                        label: "Search".into(),
                        ..Default::default()
                    }),
                }),
                ime_batch_edit: Some(RemoteImeBatchEdit {
                    ime_counter: 7,
                    field_counter: 9,
                    ..Default::default()
                }),
                ..Default::default()
            })
            .unwrap();
        tracker
    }
    #[test]
    fn unicode_delete_removes_whole_codepoint_and_uses_active_counters() {
        let mut tracker = ready();
        let ticket = tracker.state().unwrap();
        let (message, field) = tracker.prepare(&ticket, &ImeEdit::DeleteBackward).unwrap();
        let batch = message.ime_batch_edit.unwrap();
        assert_eq!((batch.ime_counter, batch.field_counter), (7, 9));
        let value = batch.edit_info[0].text_field_status.as_ref().unwrap();
        assert_eq!(value.value, "Grüße ");
        assert_eq!((value.start, value.end), (6, 6));
        tracker.commit_sent(field);
        assert!(matches!(
            tracker.prepare(&ticket, &ImeEdit::Append("stale".into())),
            Err(AndroidTvError::StaleTextField)
        ));
    }
    #[test]
    fn replace_append_clear_and_submit_are_field_scoped() {
        let mut tracker = ready();
        for (edit, expected) in [
            (ImeEdit::Replace("a🎵ö".into()), "a🎵ö"),
            (ImeEdit::Append("!".into()), "a🎵ö!"),
            (ImeEdit::Replace(String::new()), ""),
        ] {
            let (message, field) = tracker.prepare(&tracker.state().unwrap(), &edit).unwrap();
            assert_eq!(
                message.ime_batch_edit.unwrap().edit_info[0]
                    .text_field_status
                    .as_ref()
                    .unwrap()
                    .value,
                expected
            );
            tracker.commit_sent(field);
        }
        assert_eq!(
            tracker
                .prepare(&tracker.state().unwrap(), &ImeEdit::Submit)
                .unwrap()
                .0
                .key_inject
                .unwrap()
                .key_code,
            66
        );
        let old = tracker.state().unwrap();
        tracker.clear();
        assert!(matches!(
            tracker.prepare(&old, &ImeEdit::Submit),
            Err(AndroidTvError::StaleTextField)
        ));
    }
    #[test]
    fn field_loss_and_counter_changes_expire_tickets() {
        let mut tracker = ready();
        let old = tracker.state().unwrap();
        tracker
            .receive(&mut RemoteMessage {
                ime_batch_edit: Some(RemoteImeBatchEdit {
                    ime_counter: 8,
                    field_counter: 9,
                    ..Default::default()
                }),
                ..Default::default()
            })
            .unwrap();
        assert!(matches!(
            tracker.prepare(&old, &ImeEdit::Submit),
            Err(AndroidTvError::StaleTextField)
        ));
        let current = tracker.state().unwrap();
        tracker
            .receive(&mut RemoteMessage {
                ime_show_request: Some(RemoteImeShowRequest {
                    text_field_status: None,
                }),
                ..Default::default()
            })
            .unwrap();
        assert!(tracker.state().is_none());
        assert!(matches!(
            tracker.prepare(&current, &ImeEdit::Replace("lost".into())),
            Err(AndroidTvError::StaleTextField)
        ));
        let mut tracker = ready();
        tracker
            .receive(&mut RemoteMessage {
                ime_batch_edit: Some(RemoteImeBatchEdit {
                    ime_counter: 7,
                    field_counter: 10,
                    ..Default::default()
                }),
                ..Default::default()
            })
            .unwrap();
        assert!(tracker.state().is_none());
    }

    #[test]
    fn rejects_new_session_counters_and_invalid_offsets() {
        let tracker = ready();
        let mut ticket = tracker.state().unwrap();
        ticket.session_id ^= 1;
        assert!(tracker.prepare(&ticket, &ImeEdit::Submit).is_err());
        let mut ticket = tracker.state().unwrap();
        ticket.field_counter += 1;
        assert!(tracker.prepare(&ticket, &ImeEdit::Submit).is_err());
        assert_eq!(utf16_byte_index("a🎵", 2), None);
        assert_eq!(utf16_byte_index("a🎵", 3), Some(5));
        assert!(tracker
            .prepare(
                &tracker.state().unwrap(),
                &ImeEdit::Replace("a".repeat(MAX_TEXT_UTF16 + 1))
            )
            .is_err());
        assert!(!format!("{:?}", tracker.state()).contains("Grüße"));
    }
}
