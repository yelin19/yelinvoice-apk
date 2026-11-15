"""Nara Mini Myanmar text preprocessing bridge for the Android app."""
import re

from frontend.my_numbers import verbalize
from frontend.nara_frontend import phonemize, prepare_names

_LATIN = re.compile(r"[A-Za-z]")


def phonemize_myanmar(text):
    """Return Nara-vocabulary phoneme symbols for Myanmar text."""
    if not isinstance(text, str) or not text.strip():
        raise ValueError("ဖတ်ရန် မြန်မာစာသား ထည့်ပါ။")
    if _LATIN.search(text):
        raise ValueError("ဒီဗားရှင်းမှာ မြန်မာစာကိုသာ ပံ့ပိုးထားပြီး အင်္ဂလိပ်စာကို မပံ့ပိုးသေးပါ။")
    normalized = verbalize(text)
    prepared = prepare_names(normalized)
    result = phonemize(prepared)
    if not result.strip():
        raise ValueError("စာသားမှ အသံထွက်ပြောင်းလဲမှု မရရှိပါ။")
    return result
