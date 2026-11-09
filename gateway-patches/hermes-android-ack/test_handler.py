import asyncio
import importlib.util
import pathlib
import sys
import types
import unittest
from unittest.mock import patch


def _resume_live_unpersisted(ctx, live_sid, live):
    live["last_active"] = 99
    return live


class HookTest(unittest.TestCase):
    def test_unavailable_quiet_patch_falls_back_to_normal_resume(self):
        spec = importlib.util.spec_from_file_location("hermes_android_fallback_test", pathlib.Path(__file__).with_name("handler.py"))
        hook = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hook)
        server = types.ModuleType("server_fallback")
        server._resume_live_unpersisted = _resume_live_unpersisted
        server._live_session_payload = lambda *args, **kwargs: None
        received = []
        def original(frame, transport=None):
            received.append(frame)
            return {"error": "denied"} if frame.get("id") == 4 else {"result": "resumed"}
        server.dispatch = original
        requests = types.SimpleNamespace(is_response_frame=lambda frame: False)
        tui = types.ModuleType("tui_gateway")
        tui.server, tui.server_requests = server, requests
        controls = types.ModuleType("gateway.session_controls")
        class Connection:
            async def dispatch(self, frame): return None
        controls.AuthorityConnection = Connection
        session_requests = types.ModuleType("gateway.session_server_requests")
        session_requests.is_response_frame = lambda frame: False
        session_requests.may_settle = lambda *args: True
        session_requests.resolve = lambda *args, **kwargs: False
        modules = {"tui_gateway": tui, "gateway": types.ModuleType("gateway"),
                   "gateway.session_controls": controls, "gateway.session_server_requests": session_requests}
        class Transport:
            def __init__(self): self.frames = []
            def write(self, frame): self.frames.append(frame); return True
        transport = Transport()
        with patch.dict(sys.modules, modules), patch.object(hook, "_quiet_unpersisted", side_effect=RuntimeError("upstream changed")):
            hook.handle("gateway:startup")
            frame = {"id": 3, "method": "session.resume", "params": {"session_id": "x"}, "hermes_android_quiet_watch": True}
            self.assertEqual(server.dispatch(frame, transport), {"result": "resumed"})
            self.assertNotIn("hermes_android_quiet_watch", received[0])
            self.assertEqual(transport.frames[0]["method"], "hermes.android.quiet_watch_unavailable")
            server.dispatch(frame, transport)
            self.assertEqual(len(transport.frames), 1)
            class BrokenTransport:
                def write(self, frame): raise OSError("disconnected")
            self.assertEqual(server.dispatch(frame, BrokenTransport()), {"result": "resumed"})
            self.assertEqual(server.dispatch(frame, None), {"result": "resumed"})
            self.assertEqual(server.dispatch({**frame, "id": 4}, None), {"error": "denied"})

    def test_quiet_watch_is_scoped_and_acknowledgements_are_explicit(self):
        spec = importlib.util.spec_from_file_location("hermes_android_hook", pathlib.Path(__file__).with_name("handler.py"))
        hook = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hook)
        server = types.ModuleType("server")
        server._resume_live_unpersisted = _resume_live_unpersisted
        server._live_session_payload = lambda *args, **kwargs: kwargs.get("touch")
        server._stdio_transport = object()
        server.bind_transport = lambda transport: None
        server.reset_transport = lambda token: None
        server._relay_compute_host_response = lambda frame: False
        server.dispatch = lambda frame, transport=None: server._live_session_payload(touch=True)
        requests = types.SimpleNamespace(is_response_frame=lambda frame: "result" in frame,
                                         resolve_response=lambda frame, transport: frame["id"] == "yes")
        tui = types.ModuleType("tui_gateway")
        tui.server, tui.server_requests = server, requests
        controls = types.ModuleType("gateway.session_controls")
        class Connection:
            async def dispatch(self, frame):
                return "original"
        controls.AuthorityConnection = Connection
        session_requests = types.ModuleType("gateway.session_server_requests")
        session_requests.is_response_frame = requests.is_response_frame
        session_requests.may_settle = lambda *args: True
        session_requests.resolve = lambda frame, session_ok: frame["id"] == "yes"
        modules = {"tui_gateway": tui, "gateway": types.ModuleType("gateway"),
                   "gateway.session_controls": controls, "gateway.session_server_requests": session_requests}
        with patch.dict(sys.modules, modules):
            hook.handle("gateway:startup")
            hook.handle("gateway:startup")
            self.assertTrue(server.dispatch({"method": "session.resume"}))
            self.assertFalse(server.dispatch({"method": "session.resume", "hermes_android_quiet_watch": True}))
            record = {"last_active": 1}
            token = hook._quiet_watch.set(True)
            try:
                server._resume_live_unpersisted(None, "s", record)
            finally:
                hook._quiet_watch.reset(token)
            self.assertEqual(record["last_active"], 1)
            server._resume_live_unpersisted(None, "s", record)
            self.assertEqual(record["last_active"], 99)
            for request_id, expected in (("yes", True), ("no", False)):
                frame = {"id": request_id, "result": {}, "hermes_android_ack": True}
                self.assertEqual(server.dispatch(frame)["params"]["accepted"], expected)
                conn = Connection()
                conn.authority, conn.actor, conn._siblings = object(), object(), {}
                self.assertEqual(asyncio.run(conn.dispatch(frame))["params"]["accepted"], expected)


if __name__ == "__main__":
    unittest.main()
