import os
from mitmproxy import http

# Read env vars from container environment
WALLET_BACKEND_PORT = os.getenv("WALLET_BACKEND_PORT", "7001")
ISSUER_API_PORT = os.getenv("ISSUER_API_PORT", "7002")
VERIFIER_API_PORT = os.getenv("VERIFIER_API_PORT", "7003")
DEMO_WALLET_FRONTEND_PORT = os.getenv("DEMO_WALLET_FRONTEND_PORT", "7101")
DEV_WALLET_FRONTEND_PORT = os.getenv("DEV_WALLET_FRONTEND_PORT", "7104")
WEB_PORTAL_PORT = os.getenv("WEB_PORTAL_PORT", "7102")
VC_REPO_PORT = os.getenv("VC_REPO_PORT", "7103")
PG_ADMIN_PORT = os.getenv("PG_ADMIN_PORT", "8080")

ROUTE_MAP = {
    DEMO_WALLET_FRONTEND_PORT: {
        "/wallet-api": f"wallet-api:{WALLET_BACKEND_PORT}",
        "default": f"waltid-demo-wallet:{DEMO_WALLET_FRONTEND_PORT}",
    },
    DEV_WALLET_FRONTEND_PORT: {
        "/wallet-api": f"wallet-api:{WALLET_BACKEND_PORT}",
        "default": f"waltid-dev-wallet:{DEV_WALLET_FRONTEND_PORT}",
    },
    WALLET_BACKEND_PORT: {
        "default": f"wallet-api:{WALLET_BACKEND_PORT}",
    },
    ISSUER_API_PORT: {
        "default": f"issuer-api:{ISSUER_API_PORT}",
    },
    VERIFIER_API_PORT: {
        "default": f"verifier-api:{VERIFIER_API_PORT}",
    },
    WEB_PORTAL_PORT: {
        "default": f"web-portal:{WEB_PORTAL_PORT}"
    },
    VC_REPO_PORT: {
        "default": f"vc-repo:{VC_REPO_PORT}"
    },
    PG_ADMIN_PORT: {
        "default": f"phpmyadmin:{PG_ADMIN_PORT}"
    }
}

def request(flow: http.HTTPFlow) -> None:
    port = flow.request.headers.get("X-Target-Port")
    path = flow.request.path

    if not port or port not in ROUTE_MAP:
        return

    for prefix, target in ROUTE_MAP[port].items():
        if prefix == "default":
            continue
        if path.startswith(prefix):
            host, backend_port = target.split(":")
            flow.request.host = host
            flow.request.port = int(backend_port)
            return

    default_target = ROUTE_MAP[port].get("default")
    if default_target:
        host, backend_port = default_target.split(":")
        flow.request.host = host
        flow.request.port = int(backend_port)