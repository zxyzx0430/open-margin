# 清衡 · Open Margin

- **中文产品名：** 清衡
- **English product name:** Open Margin
- **GitHub slug:** open-margin
- **设计理念：** 清楚收支，衡量得失，为生活留有余地。
- **Product idea:** Make room for what matters.

This is the name of the integrated Personal Finance System, not a claim to own the upstream Wallos or Seamless Bookkeeping projects. Keep their licenses and attributions.

## Compatibility rules

- The website retains its original \`/finance/wallos/\` URL, all API endpoints, SQLite databases, auth sessions, and deployed service paths.
- Android changes only \`@string/app_name\`; the application ID remains \`com.bookkeeping.app\`, so an app update preserves its existing local data.
- Web PWA names and notification fallbacks use 清衡, but manifest \`id\`, \`start_url\`, and \`scope\` remain unchanged for installed-app identity.
- The upstream Wallos and Seamless Bookkeeping attributions remain in README, LICENSE / THIRD_PARTY, About and the codebase.
- The old GitHub URL may redirect after renaming; update the production git remote explicitly.
