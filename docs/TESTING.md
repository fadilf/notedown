# Device verification

Do not treat emulator unit/instrumentation results as proof of Samsung/Gboard compatibility or production certification. Run this matrix on Android 14, 15 and 16, including a physical Samsung device, with Gboard and Samsung Keyboard. Also smoke-test API 29 minimum support.

| Area | Procedure | Expected |
|---|---|---|
| Setup | Fresh install, decline/grant mic, decline notification, enable/disable accessibility | Explanations, no capture before permission, no crash; notification permission optional |
| IME detection | Open/close keyboard, switch hardware keyboard, split/floating keyboard, rotate, change display size | Mic only with an actual visible IME and non-password editable field; controls remain reachable |
| Immediate capture | Tap overlay once and speak immediately | Compact activity starts mic automatically, no second tap; expanded recording appears only after start |
| Return to editor | Leave Accessibility Settings in Notedown's task after setup, switch to an editor, and tap the floating mic | Recording starts after one tap and the original editor returns; Settings is not brought forward |
| Save/cancel | Stop after several seconds; repeat with Cancel | Stop retains private audio and queues STT; Cancel discards and makes no API request |
| Modern foreground service | Record on Android 14–16, background app, use notification Stop/Cancel | Visible recording entry activity, microphone FGS indicator and controls; no background-start exceptions |
| Insertion | Dictate into Gmail, Chrome and a messenger with text/selection before/after cursor | Current selection at completion is replaced; surrounding text retained |
| Focus safety | Switch field/app or edit original while processing; switch back | Completion inserts into the currently focused field at its latest selection, preserving surrounding content; with no eligible field, use Copy/history |
| Passwords | Focus password/PIN fields | No capture target or insertion |
| Network errors | Airplane mode before Stop, invalid key, exhausted credits, rate limit, timeout, unsupported format | Audio retained, actionable status; offline queue resumes; explicit retry creates a version |
| Models | Refresh catalog, choose unavailable model, retranscribe with another model | Availability warning; no silent model substitution or version overwrite |
| Cleanup | Disable, enable, change prompt/model, force cleanup failure | Raw retained; cleanup is separate; failure yields usable raw text |
| Audio | Play/pause/resume, silence, short clip, headset disconnect, another app takes mic, ten-minute limit | Valid capture/playback or recoverable error; no uncaught exceptions |
| WAV | Switch compatibility mode; retranscribe existing M4A | WAV conversion uploaded; retained original unchanged |
| Process death | Kill process after save/while queued/processing; reopen; force-stop separately | WorkManager resumes ordinary death, orphan files shown; force-stop waits for reopen; no insertion into new field |
| Deletion | Delete one/all with confirmation while queued | Audio and versions removed, queued work cancelled, usage ledger retained |
| Privacy | Inspect logcat, app data, backup behavior, clipboard | No keys/transcripts/audio logged; credential encrypted; clipboard only changes after explicit Copy |
| Usage | Provider response with/without `usage.cost`, cleanup request, delete history | Exact reported totals; missing cost remains unknown; ledger survives history deletion |

Live endpoint validation must use an explicitly supplied test key with credits. Confirm at least one STT model accepts M4A and WAV, and one text model completes cleanup. Never put keys in source, test fixtures, shell logs or screenshots.
