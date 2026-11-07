# Device verification

Do not treat emulator unit/instrumentation results as proof of Samsung/Gboard compatibility or production certification. Run this matrix on Android 14, 15 and 16, including a physical Samsung device, with Gboard and Samsung Keyboard. Also smoke-test API 29 minimum support.

| Area | Procedure | Expected |
|---|---|---|
| Setup | Fresh install, decline/grant mic, decline notification, enable/disable accessibility | Explanations, no capture before permission, no crash; notification permission optional |
| IME detection | Open/close keyboard, switch hardware keyboard, split/floating keyboard, rotate, change display size | Mic only with an actual visible IME and non-password editable field; controls remain reachable |
| Repositioning | Drag the mic to either edge and different heights; close/reopen keyboard, restart service, rotate and change display size; drag recording controls; reset position in Settings | Dragging never triggers a button; side/height persist; controls stay inside system bars and above keyboard; tap still starts capture once; reset returns above keyboard |
| Immediate capture | Tap overlay once and speak immediately | Compact activity starts mic automatically, no second tap; expanded recording appears only after start |
| Return to editor | Leave Accessibility Settings in Notedown's task after setup, switch to an editor, and tap the floating mic | Recording starts after one tap and the original editor returns; Settings is not brought forward |
| Save/cancel | Stop after several seconds; repeat with Cancel | Stop retains private audio and queues STT; Cancel discards and makes no API request |
| Modern foreground service | Record on Android 14–16, background app, use notification Stop/Cancel | Visible recording entry activity, microphone FGS indicator and controls; no background-start exceptions |
| Insertion | Dictate into Gmail, Chrome and a messenger with text/selection before/after cursor | Current selection at completion is replaced; surrounding text retained |
| Focus safety | Switch field/app or edit original while processing; switch back | Completion inserts into the currently focused field at its latest selection, preserving surrounding content; with no eligible field, use Copy/history |
| Passwords | Focus password/PIN fields | No capture target or insertion |
| Network errors | Airplane mode before Stop, invalid key, exhausted credits, rate limit, timeout, unsupported format | Audio retained, actionable status; offline queue resumes; explicit retry creates a version |
| Models | Refresh catalog, choose unavailable model, retranscribe with another model | Availability warning; no silent model substitution or version overwrite |
| Model table | Open both model pickers; search, scroll, select a row, and reopen; repeat with large fonts and the keyboard visible | Input/output columns show USD per 1M tokens; selected row is marked; no rounded row clipping; Model, Input, and Output columns are visible together without sideways scrolling, including on narrow screens and with large fonts; missing rates show Unknown; small paid rates remain nonzero; pricing details expose special rates and tiers |
| Model sorting | Tap each table heading, tap it again, then search and select a model | Model sorts alphabetically; Input/Output sort by exact numeric rate; arrows show ascending/descending order; unknown rates stay last in both directions; all three columns remain visible |
| Cleanup | Disable, enable, change prompt/model, force cleanup failure | Raw retained; cleanup is separate; failure yields usable raw text |
| Audio | Play/pause/resume, silence, short clip, headset disconnect, another app takes mic, ten-minute limit | Valid capture/playback or recoverable error; no uncaught exceptions |
| WAV | Switch compatibility mode; retranscribe existing M4A | WAV conversion uploaded; retained original unchanged |
| Process death | Kill process after save/while queued/processing; reopen; force-stop separately | WorkManager resumes ordinary death, orphan files shown; force-stop waits for reopen; no insertion into new field |
| Deletion | Delete one/all with confirmation while queued | Audio and versions removed, queued work cancelled, usage ledger retained |
| Privacy | Inspect logcat, app data, backup behavior, clipboard | No keys/transcripts/audio logged; credential encrypted; clipboard only changes after explicit Copy |
| Usage | Provider response with/without `usage.cost`, cleanup request, delete history | Exact reported totals; missing cost remains unknown; ledger survives history deletion |

Live endpoint validation must use an explicitly supplied test key with credits. Confirm at least one STT model accepts M4A and WAV, and one text model completes cleanup. Never put keys in source, test fixtures, shell logs or screenshots.
