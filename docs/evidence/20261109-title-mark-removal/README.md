# Title decoration verification

Change: 606aecc. Tested source: 3f0f4919a8fd30bdd37c07c3e17d0dccfef89f02.

Official WeChat CLI and automator rendered five page states and checked 13 headings: zero left padding, zero left border, no studio/creation mark nodes. No captured runtime exceptions. All WXML/WXSS in the isolated project match the source byte for byte.

Home uses the current project directly. Protected studio, digital presenter, AI video and recharge pages use an isolated app.js fixture overriding the studio guard, wallet session resolution and network requests. Page display state is injected with setData. The main project session is unchanged.

Screenshots home, studio, ai-video and recharge were visually reviewed; digital has computed-style evidence and a saved screenshot. npm run check passed; see local-checks.log.

Real login, generation, payment, full role workflows and physical devices are outside this visual check. No remote push or Mini Program upload.
