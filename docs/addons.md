# Add-on API 1

The Android host downloads the reviewed public catalog from AntoinS/cleanframe-addons. See that repository's README, package.schema.json and examples for the public authoring contract. API 1 uses declarative JSON packages and the existing Android/JSON/network libraries; downloaded JavaScript, HTML and APKs are not supported.

Spotify's native adapter remains part of the host API. Its installed package enables the widget; removing the package stops Home observation and removes the widget. Account credentials and appearance preferences are deliberately retained, as stated in the confirmation dialog. Use Spotify → Sign out before removal to clear CleanFrame's account grant. The native Spotify application is unaffected.

A first upgrade seeds the Spotify package from bundled assets. Later package state is kept in addons.json through Android AtomicFile, independently of Home preferences. Saved installations work offline. Failed downloads, validation or disk writes retain previous state. Explicit refresh fetches catalog changes; downloads are never automatic. A single executor serializes operations; the UI receives completion asynchronously.

Third-party text and clock widgets are rendered as textContent using host templates, with at most three active cards. Cards occupy a fixed Home region and are hidden while the Spotify player is expanded. Clock timezone is an optional valid IANA identifier. Long text is clipped to the card; authors should keep content concise. Arbitrary rendering, custom scripts, network data providers and additional native capabilities require a later host API revision.

Management is available at Settings → Add-ons on the TV. The Mac preview can show the layout; a normal browser has no native download/storage access and reports that honestly. Tests use an explicit synthetic adapter, never real account credentials.
