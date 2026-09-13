# WebView device test checklist

Repository CI proves the app compiles. These items require a real Android device and current Android System WebView.

## Each built-in AI

For ChatGPT, Claude, Gemini, Grok and DeepSeek:

- page loads over HTTPS
- login page renders
- login persists after switching away and back
- text composer can send a prompt
- new-chat action works or safely falls back
- stop action works while generating
- website file chooser opens and selected file reaches the site
- back / forward / reload work

## Browser capabilities

- download starts through Android DownloadManager
- camera request asks Android permission and works after approval
- microphone request asks Android permission and works after approval
- geolocation request asks Android permission and works after approval
- popup/OAuth windows open and can close back to the parent page
- external schemes open the appropriate installed app
- WebView renderer crash is handled without killing the whole AIHub process

## Security

- HTTP provider home URLs are rejected by packaged-rule validation
- cleartext traffic remains disabled in the manifest
- mixed content remains blocked
- direct file access remains disabled
- media/geolocation permission behavior is reviewed on every browser-host change

## Known compatibility boundary

Embedded WebView is not full Chrome. If a provider or identity service explicitly refuses embedded-browser authentication, AIHub should report that limitation rather than spoofing Chrome or bypassing the provider policy.
