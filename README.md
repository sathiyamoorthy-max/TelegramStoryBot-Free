# TelegramStoryBot-Free

Independent Render-ready Telegram Story Bot.

## Access model

- **OWNER**: all stories, unlimited episode usage, full owner controls.
- **ADMIN**: only stories assigned by OWNER; admin management features remain.
- **USER**: only stories assigned by OWNER/ADMIN; standard safety quota remains.
- Subscription, global-trial and reward access are disabled for this deployment.

## Required Render environment variables

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_BOT_USERNAME`
- `TELEGRAM_OWNER_ID`
- `TELEGRAM_OWNER_USERNAME`
- `DB_URL` — JDBC MySQL URL, for example `jdbc:mysql://host:3306/storybot`
- `DB_USERNAME`
- `DB_PASSWORD`

Optional:

- `TELEGRAM_ADMIN_IDS` — comma-separated numeric Telegram IDs for bootstrap admins.
- `GROQ_API_KEY` — only if Groq features are enabled.

Never commit Telegram tokens, DB passwords, Google credentials, Groq keys, or other secrets to GitHub.

## Render

The repo contains a Dockerfile, Render Blueprint, and `/health` endpoint. Create a Render Blueprint/Web Service from this repo, then enter the required environment values in Render.
