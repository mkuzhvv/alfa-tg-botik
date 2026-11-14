"""Локальный tokenizer Qwen; загрузка словаря не передаёт тексты корпуса."""
from functools import lru_cache
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
TOKENIZER = ROOT / "data/source/models/qwen3.5-tokenizer.json"
META = TOKENIZER.with_suffix(".meta.json")
URL = "https://huggingface.co/Qwen/Qwen3.5-9B/resolve/main/tokenizer.json"


def ensure_tokenizer():
    if TOKENIZER.exists() and META.exists():
        return
    with urlopen(URL, timeout=60) as response:
        data = response.read()
        revision = response.headers.get("x-repo-commit")
    TOKENIZER.parent.mkdir(parents=True, exist_ok=True)
    temporary = TOKENIZER.with_suffix(".partial")
    temporary.write_bytes(data)
    temporary.replace(TOKENIZER)
    META.write_text(json.dumps({"source": URL, "revision": revision,
                               "sha256": hashlib.sha256(data).hexdigest()}, indent=2))


@lru_cache(maxsize=1)
def _load():
    from tokenizers import Tokenizer
    metadata = json.loads(META.read_text())
    if metadata["source"] != URL or hashlib.sha256(TOKENIZER.read_bytes()).hexdigest() != metadata["sha256"]:
        raise ValueError("Tokenizer не соответствует контрольной сумме")
    return Tokenizer.from_file(str(TOKENIZER))


def local_token_counter(model):
    if not model.startswith("qwen3.5:") or not TOKENIZER.exists() or not META.exists():
        return None
    tokenizer = _load()
    return lambda text: len(tokenizer.encode(text, add_special_tokens=False).ids)
