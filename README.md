# TelegramStoryBot-Free

Independent Telegram Story Bot deployment.

Access model:
- OWNER: every story and unlimited episode usage.
- ADMIN: only stories assigned by OWNER.
- USER: only stories assigned by OWNER/ADMIN.
- Subscription, global-trial and reward access are disabled.

Required Render environment variables:
- TELEGRAM_BOT_TOKEN
- TELEGRAM_BOT_USERNAME
- TELEGRAM_OWNER_ID
- TELEGRAM_OWNER_USERNAME
- DB_URL
- DB_USERNAME
- DB_PASSWORD

Optional:
- TELEGRAM_ADMIN_IDS: comma-separated numeric Telegram IDs.
- GROQ_API_KEY: only if Groq features are enabled.

Keep all tokens, passwords and API keys in Render environment variables only.
