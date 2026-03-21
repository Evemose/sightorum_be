#!/usr/bin/env python3
"""
Imports conv_dump.json into the chat_memory table used by JsonbChatMemoryRepository.

Reconstructs the conversation as properly typed rows:
  THINKING  – extended thinking blocks
  ASSISTANT – text + toolCalls
  TOOL      – tool responses

Usage:
  python import_conv_dump.py [--conversation-id <id>] [--dry-run]
"""

import argparse
import json
import psycopg2
import uuid

DUMP_PATH = "conv_dump.json"

DB_HOST = "3.75.210.208"
DB_PORT = 5444
DB_NAME = "mydatabase"
DB_USER = "myuser"
DB_PASS = "mypassword"


def find_tool_result(dump: dict, tool_id: str, tool_name: str) -> str | None:
    # tool-{name}-{id} (JSON object results)
    key1 = f"tool-{tool_name}-{tool_id}"
    if key1 in dump:
        val = dump[key1]
        return json.dumps(val) if isinstance(val, dict) else str(val)

    # {id}:submit (deferred job submission, returns UUID)
    key2 = f"{tool_id}:submit"
    if key2 in dump:
        return str(dump[key2])

    # {id} directly (text results like engineerFeature)
    if tool_id in dump:
        val = dump[tool_id]
        return json.dumps(val) if isinstance(val, dict) else str(val)

    return None


def reconstruct_conversation(dump: dict) -> list[tuple[str, dict]]:
    """Convert the dump into ordered (message_type, payload) tuples."""
    rows: list[tuple[str, dict]] = []

    stream_keys = sorted(
        [k for k in dump if k.startswith("llm-stream-")],
        key=lambda k: int(k.rsplit("-", 1)[1]),
    )

    for stream_key in stream_keys:
        msg = dump[stream_key]
        content_blocks = msg.get("content", [])

        # 1) THINKING messages
        for block in content_blocks:
            if block["type"] == "thinking" and block.get("thinking"):
                rows.append(("THINKING", {"text": block["thinking"]}))

        # 2) ASSISTANT message: text + toolCalls
        text_parts = [block["text"] for block in content_blocks if block["type"] == "text"]
        assistant_text = "\n\n".join(text_parts)

        tool_use_blocks = [block for block in content_blocks if block["type"] == "tool_use"]
        tool_calls = None
        if tool_use_blocks:
            tool_calls = [
                {
                    "id": tc["id"],
                    "type": "function",
                    "name": tc["name"],
                    "arguments": json.dumps(tc.get("input", {})),
                }
                for tc in tool_use_blocks
            ]

        payload: dict = {"text": assistant_text}
        if tool_calls:
            payload["toolCalls"] = tool_calls

        if assistant_text or tool_calls:
            rows.append(("ASSISTANT", payload))

        # 3) TOOL response message
        if tool_use_blocks:
            tool_responses = []
            for tc in tool_use_blocks:
                result = find_tool_result(dump, tc["id"], tc["name"])
                tool_responses.append({
                    "id": tc["id"],
                    "name": tc["name"],
                    "responseData": result or "",
                })
            rows.append(("TOOL", {"toolResponses": tool_responses}))

    return rows


def insert_rows(rows: list[tuple[str, dict]], conversation_id: str, dry_run: bool = False):
    if dry_run:
        print(f"Would insert {len(rows)} rows for conversation '{conversation_id}':\n")
        for i, (msg_type, payload) in enumerate(rows):
            text_preview = (payload.get("text") or "")[:80]
            tc = payload.get("toolCalls")
            tr = payload.get("toolResponses")
            extra = ""
            if tc:
                extra = f" | {len(tc)} tool calls"
            if tr:
                extra = f" | {len(tr)} tool responses"
            print(f"  [{i:3d}] {msg_type:12s}{extra:30s} | {text_preview}")
        return

    conn = psycopg2.connect(
        host=DB_HOST, port=DB_PORT, dbname=DB_NAME,
        user=DB_USER, password=DB_PASS,
        options="-c TimeZone=Europe/Kyiv",
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                "delete from chat_memory where conversation_id = %s",
                (conversation_id,),
            )
            for msg_type, payload in rows:
                cur.execute(
                    "insert into chat_memory (conversation_id, message_type, payload) "
                    "values (%s, %s, %s::jsonb)",
                    (conversation_id, msg_type, json.dumps(payload)),
                )
        conn.commit()
        print(f"Inserted {len(rows)} rows for conversation '{conversation_id}'.")
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description="Import conv_dump.json into chat_memory")
    parser.add_argument(
        "--conversation-id", default=None,
        help="Conversation ID to use (default: auto-generated UUID)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print rows without inserting")
    parser.add_argument("--dump", default=DUMP_PATH, help="Path to the dump JSON file")
    args = parser.parse_args()

    conversation_id = args.conversation_id or str(uuid.uuid4())

    with open(args.dump) as f:
        dump = json.load(f)

    rows = reconstruct_conversation(dump)
    print(f"Reconstructed {len(rows)} messages from dump.")
    insert_rows(rows, conversation_id, dry_run=args.dry_run)
    if not args.dry_run:
        print(f"Conversation ID: {conversation_id}")


if __name__ == "__main__":
    main()
