<div align="center">
 <h1>Android Sample Project</h1>
 <span>by </span><a href="https://walt.id">walt.id</a>
 <p>Sample project showcasing key & DID creation, text signing, and signed content verification.<p>

<a href="https://walt.id/community">
<img src="https://img.shields.io/badge/Join-The Community-blue.svg?style=flat" alt="Join community!" />
</a>
<a href="https://twitter.com/intent/follow?screen_name=walt_id">
<img src="https://img.shields.io/twitter/follow/walt_id.svg?label=Follow%20@walt_id" alt="Follow @walt_id" />
</a>

## Features

1. **Key Generation**: Generate keys using different algorithms (RSA, Secp256r1).

2. **Text Signing**: Sign text with RAW or JWS signing options. 

3. **DID Creation**: Create DIDs (did:key, did:jwk) based on generated keys. 

4. **Verification**: Verify signed text using generated keys and DIDs.

## Screenshots

![Key Generation](screenshots/key_generation_screen.png) ![Retrieve Public Key](screenshots/retrieve_public_key_screen.png) ![Biometrics Prompt](screenshots/biometrics_prompt.png) ![DID Creation](screenshots/did_creation_screen.png) ![Signing Text](screenshots/signing_text_screen.png) ![Verification](screenshots/verification_screen.png)

## How to Run

1. Clone the repository.
2. Open the project in Android Studio.
3. Set your `sdk.dir` in `local.properties` and then enable the Android build
   with `enableAndroidBuild=true` in `gradle.properties`.
4. Run the application on your device or emulator.

## Join the community

* Connect and get the latest updates: [Discord](https://discord.gg/AW8AgqJthZ) | [Newsletter](https://walt.id/newsletter) | [YouTube](https://www.youtube.com/channel/UCXfOzrv3PIvmur_CmwwmdLA) | [Twitter](https://mobile.twitter.com/walt_id)
* Get help, request features and report bugs: [GitHub Issues ](https://github.com/walt-id/waltid-identity/issues)

## License

**Licensed under the [Apache License, Version 2.0](https://github.com/walt-id/waltid-ssikit/blob/master/LICENSE).**
