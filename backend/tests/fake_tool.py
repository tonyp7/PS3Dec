#!/usr/bin/env python3
"""Stand-in for PS3Dec used by job-lifecycle tests.

Called like the real tool: ``fake_tool <mode> <type> [hex] <in> <out>``. Only the last two
arguments are used. Behaviour comes from the environment so tests can change it per job:

  FAKE_TOOL_MODE   ok (default) | fail | hang | stubborn (hang, ignoring SIGTERM) | short | slowexit
  FAKE_TOOL_ARGV_FILE  if set, the received arguments are written there, one per line
  FAKE_TOOL_CHUNK  bytes copied per step (default 1 MiB)
  FAKE_TOOL_DELAY  seconds slept after each step (default 0)

Like the real tool it prints a key line on stdout and diagnostics on stderr.
"""

import os
import signal
import sys
import time

FAKE_KEY_LINE = "Decryption key:00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff"


def main() -> int:
    src, dst = sys.argv[-2], sys.argv[-1]
    mode = os.environ.get("FAKE_TOOL_MODE", "ok")
    chunk = int(os.environ.get("FAKE_TOOL_CHUNK", str(1 << 20)))
    delay = float(os.environ.get("FAKE_TOOL_DELAY", "0"))

    argv_file = os.environ.get("FAKE_TOOL_ARGV_FILE")
    if argv_file:
        with open(argv_file, "w") as f:
            f.write("\n".join(sys.argv[1:]) + "\n")
    if mode == "stubborn":
        signal.signal(signal.SIGTERM, signal.SIG_IGN)

    print(FAKE_KEY_LINE, flush=True)
    print("PS3Dec fake", file=sys.stderr, flush=True)
    size = os.path.getsize(src)
    limit = size - 1 if mode == "short" else size

    with open(src, "rb") as fin, open(dst, "wb") as fout:
        done = 0
        while done < limit:
            data = fin.read(min(chunk, limit - done))
            if not data:
                break
            fout.write(data)
            fout.flush()
            done += len(data)
            if mode == "fail" and done >= size // 2:
                print("ERROR: Failed to read from file", file=sys.stderr, flush=True)
                return 1
            if mode in ("hang", "stubborn") and done >= size // 2:
                print("hanging", file=sys.stderr, flush=True)
                while True:
                    time.sleep(60)
            if delay:
                time.sleep(delay)
    if mode == "slowexit":
        time.sleep(2)
    print("done", file=sys.stderr, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
