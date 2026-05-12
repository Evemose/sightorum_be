import asyncio
import json
import logging
import os
import psutil
import urllib.request
from urllib.error import URLError, HTTPError

logger = logging.getLogger(__name__)

CPU_BUSY_THRESHOLD_PCT = 5.0
POLL_INTERVAL_S = 30
PROTECTION_MINUTES = 15
RENEW_INTERVAL_S = 300


async def run_task_protection_loop():
    agent_uri = os.environ.get("ECS_AGENT_URI")
    if not agent_uri:
        logger.info("ECS_AGENT_URI not set; task scale-in protection disabled")
        return

    url = f"{agent_uri}/task-protection/v1/state"
    psutil.cpu_percent(interval=None)

    loop = asyncio.get_event_loop()
    protected = False
    last_renew = 0.0

    while True:
        try:
            cpu = psutil.cpu_percent(interval=None)
            busy = cpu >= CPU_BUSY_THRESHOLD_PCT
            now = loop.time()
            transition = busy != protected
            renew_due = busy and (now - last_renew) >= RENEW_INTERVAL_S
            if transition or renew_due:
                body = {"ProtectionEnabled": busy}
                if busy:
                    body["ExpiresInMinutes"] = PROTECTION_MINUTES
                await loop.run_in_executor(None, _put_state, url, body)
                if transition:
                    logger.info(f"task protection -> {body} (cpu={cpu:.1f}%)")
                protected = busy
                if busy:
                    last_renew = now
        except Exception as e:
            logger.warning(f"task protection loop error: {e}")
        await asyncio.sleep(POLL_INTERVAL_S)


def _put_state(url: str, body: dict) -> None:
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        method="PUT",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status >= 400:
                logger.warning(f"task protection PUT {resp.status}: {resp.read().decode(errors='replace')[:500]}")
    except HTTPError as e:
        body_str = ""
        try:
            body_str = e.read().decode(errors="replace")[:500]
        except Exception:
            pass
        logger.warning(f"task protection PUT {e.code}: {body_str or e.reason}")
    except URLError as e:
        logger.warning(f"task protection PUT network error: {e}")
