"""Add an opt-in receipt for Android's server-request answers at gateway startup."""
from __future__ import annotations

import ast
import contextvars
import inspect
import textwrap
import __future__

_quiet_watch = contextvars.ContextVar("hermes_android_quiet_watch", default=False)


def _quiet_unpersisted(original, namespace):
    """Build a fail-closed version of the known live-unpersisted path without its touch."""
    tree = ast.parse(textwrap.dedent(inspect.getsource(original)))

    class GuardTouch(ast.NodeTransformer):
        count = 0

        def visit_Assign(self, node):
            if (len(node.targets) == 1 and isinstance(node.targets[0], ast.Subscript)
                    and isinstance(node.targets[0].value, ast.Name) and node.targets[0].value.id == "live"
                    and isinstance(node.targets[0].slice, ast.Constant) and node.targets[0].slice.value == "last_active"):
                self.count += 1
                return ast.copy_location(ast.If(
                    test=ast.UnaryOp(op=ast.Not(), operand=ast.Call(
                        func=ast.Attribute(value=ast.Name(id="_hermes_android_quiet_watch", ctx=ast.Load()),
                                           attr="get", ctx=ast.Load()), args=[], keywords=[])),
                    body=[node], orelse=[]), node)
            return self.generic_visit(node)

    transformer = GuardTouch()
    tree = transformer.visit(tree)
    if transformer.count != 1:
        raise RuntimeError("gateway watch patch: live-session touch changed")
    local = {}
    exec(compile(ast.fix_missing_locations(tree), inspect.getsourcefile(original) or "<quiet-watch>",
                 "exec", flags=__future__.annotations.compiler_flag), namespace, local)
    return local[original.__name__]


def _ack(frame, accepted):
    return {"jsonrpc": "2.0", "method": "hermes.android.answer_ack",
            "params": {"id": frame.get("id"), "accepted": bool(accepted)}}


def handle(event_type, context=None):
    if event_type != "gateway:startup":
        return
    from tui_gateway import server, server_requests
    from gateway.session_controls import AuthorityConnection
    from gateway.session_server_requests import is_response_frame, may_settle, resolve

    if not getattr(server.dispatch, "_hermes_android_ack", False):
        original = server.dispatch
        try:
            original_payload = server._live_session_payload
            server.__dict__["_hermes_android_quiet_watch"] = _quiet_watch
            quiet_unpersisted = _quiet_unpersisted(server._resume_live_unpersisted, server.__dict__)
            quiet_supported = True
        except Exception as exc:
            print(f"[hermes-android] quiet watcher unavailable: {exc}", flush=True)
            quiet_supported = False

        if quiet_supported:
            server._resume_live_unpersisted = quiet_unpersisted

            def live_payload(*args, **kwargs):
                if _quiet_watch.get():
                    kwargs["touch"] = False
                return original_payload(*args, **kwargs)

            server._live_session_payload = live_payload

        def dispatch(frame, transport=None):
            if (isinstance(frame, dict) and frame.get("method") == "session.resume"
                    and frame.get("hermes_android_quiet_watch") is True):
                clean = dict(frame)
                clean.pop("hermes_android_quiet_watch", None)
                if not quiet_supported:
                    # A compatibility failure must not stop monitoring. Notify this socket once;
                    # warning delivery itself must never prevent the normal resume.
                    try:
                        if transport is not None and not getattr(transport, "_hermes_quiet_warning_sent", False):
                            sent = transport.write({"jsonrpc": "2.0",
                                "method": "hermes.android.quiet_watch_unavailable", "params": {}})
                            if sent:
                                transport._hermes_quiet_warning_sent = True
                    except Exception as exc:
                        print(f"[hermes-android] quiet watcher warning failed: {exc}", flush=True)
                    return original(clean, transport)
                token = _quiet_watch.set(True)
                try:
                    return original(clean, transport)
                finally:
                    _quiet_watch.reset(token)
            if not (isinstance(frame, dict) and frame.get("hermes_android_ack") is True
                    and server_requests.is_response_frame(frame)):
                return original(frame, transport)
            target = transport or server._stdio_transport
            token = server.bind_transport(target)
            try:
                accepted = server_requests.resolve_response(frame, target)
                if not accepted:
                    # A relay confirms forwarding only, not whether the owner settled it.
                    server._relay_compute_host_response(frame)
                return _ack(frame, accepted)
            finally:
                server.reset_transport(token)

        dispatch._hermes_android_ack = True
        server.dispatch = dispatch

    if not getattr(AuthorityConnection.dispatch, "_hermes_android_ack", False):
        original_authority = AuthorityConnection.dispatch

        async def dispatch_authority(self, frame):
            if not (isinstance(frame, dict) and frame.get("hermes_android_ack") is True
                    and is_response_frame(frame)):
                return await original_authority(self, frame)

            def entitled(session_id, events):
                return any(may_settle(conn.authority, conn.actor, session_id, events)
                           for conn in (self, *self._siblings.values()))

            return _ack(frame, resolve(frame, session_ok=entitled))

        dispatch_authority._hermes_android_ack = True
        AuthorityConnection.dispatch = dispatch_authority
