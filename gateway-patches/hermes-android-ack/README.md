# Android gateway support

Install `HOOK.yaml` and `handler.py` into `~/.hermes/hooks/hermes-android-ack/` and restart the gateway. The startup hook adds opt-in receipts for Android notification replies on both gateway dispatch paths. A receipt means the local gateway settled the request; forwarding to a remote owner remains unconfirmed.

For marked legacy `session.resume` requests, the hook suppresses the activity-time update on the two existing live-session paths. It checks the upstream unpersisted-session function before patching; if that shape changes it uses ordinary resume and sends a compatibility warning instead of blocking monitoring. Canonical session authority uses its own attach path. An inactive stored session may still need to be recreated by the gateway and can acquire a new live activity time.

When no receipt arrives, Android reports the answer as uncertain and directs the user to inspect the chat before trying again. Run `python3 gateway-patches/hermes-android-ack/test_handler.py` after updating the hook; also validate with the installed gateway version and a real device after upstream updates.
