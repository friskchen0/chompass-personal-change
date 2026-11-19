# Personal fork — kept for study, not maintained

This repository is a **personal snapshot** of [Chompass](https://codeberg.org/fitguy/Chompass)
by *fitguy*, taken at upstream `6.3.0-beta.5` (commit `baf3d29`, 2026-11-16).

I am **not** the author of Chompass, and this fork is **not** the maintained version.
Upstream on Codeberg is the only version that receives fixes. The upstream author is
already working on a similar per-feature AI selection feature, so this copy exists only
to keep the code and my experiment for later learning.

## License

Unchanged: **GPL-3.0-or-later**. See [LICENSE](LICENSE).
Copyright remains with the original authors. Third-party components keep their own
licenses — see [`docs/NOTICE.md`](docs/NOTICE.md) and
[`docs/ASSET_CREDITS.md`](docs/ASSET_CREDITS.md).

## What this fork adds

One branch, one commit (`feat/per-feature-ai-profiles`): per-feature AI profile routing,
so food photos can run on Gemini while the AI Coach and goal recalculation run on a
different profile (for example a DeepSeek OpenAI-compatible endpoint), each with its own
provider, model, base URL, and API key. It also adds an error-classification gate for the
fallback profile and a new fallback path for the Coach, with unit and MockWebServer tests.

The change was developed with AI assistance, then read, built, and tested locally. It has
**not** been reviewed by the Chompass author, has **not** been sent upstream, and is not
endorsed by them.

## What is deliberately not here

- No API keys, signing keys, keystores, or personal health data.
- No APK committed to git (build it yourself — see [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)).
