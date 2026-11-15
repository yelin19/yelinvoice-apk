"""Offline Myanmar + English phonemization bridge for the Android Nara ONNX runtime."""
import os
import re

from frontend.my_numbers import verbalize
from frontend.nara_frontend import phonemize, prepare_names

_LATIN = re.compile(r"[A-Za-z]")
_LATIN_RUN = re.compile(r"[A-Za-z][A-Za-z'. -]*[A-Za-z]|[A-Za-z]")
_ENGLISH_G2P = None
_NLTK_DATA_READY = False

# CMU ARPABET to IPA symbols represented by Nara's English-capable vocabulary.
_ARPA_TO_IPA = {
    "AA": "ɑ", "AE": "æ", "AH": "ə", "AO": "ɔ", "AW": "aʊ", "AY": "aɪ",
    "EH": "ɛ", "ER": "ɚ", "EY": "eɪ", "IH": "ɪ", "IY": "i", "OW": "oʊ",
    "OY": "ɔɪ", "UH": "ʊ", "UW": "u",
    "B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "F": "f", "G": "ɡ",
    "HH": "h", "JH": "dʒ", "K": "k", "L": "l", "M": "m", "N": "n",
    "NG": "ŋ", "P": "p", "R": "ɹ", "S": "s", "SH": "ʃ", "T": "t",
    "TH": "θ", "V": "v", "W": "w", "Y": "j", "Z": "z", "ZH": "ʒ",
}
_VOWELS = {"AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"}
_PUNCT = {";": ",", ":": ",", "—": ",", "–": ","}


def configure_english_data(data_directory):
    """Point NLTK at the resources bundled in APK assets; never fetch data online."""
    global _NLTK_DATA_READY
    import nltk

    root = os.path.abspath(data_directory)
    if root not in nltk.data.path:
        nltk.data.path.insert(0, root)
    try:
        nltk.data.find("corpora/cmudict.zip")
        nltk.data.find("taggers/averaged_perceptron_tagger.zip")
    except LookupError as error:
        raise ValueError("အင်္ဂလိပ်အသံထွက်အတွက် လိုအပ်သော offline data ဖိုင် မတွေ့ပါ။") from error

    # g2p-en checks these local resources during import. Prevent any fallback download.
    nltk.download = lambda *args, **kwargs: False
    _NLTK_DATA_READY = True


def _english_g2p():
    global _ENGLISH_G2P
    if not _NLTK_DATA_READY:
        raise ValueError("အင်္ဂလိပ်စာအတွက် offline pronunciation data ကို အရင်ပြင်ဆင်ပါ။")
    if _ENGLISH_G2P is None:
        try:
            from g2p_en import G2p
            _ENGLISH_G2P = G2p()
        except Exception as error:
            raise ValueError("အင်္ဂလိပ်အသံထွက်မော်ဒယ်ကို စတင်မရပါ။") from error
    return _ENGLISH_G2P


def _arpabet_to_ipa(tokens):
    output = []
    for token in tokens:
        if token == " ":
            output.append(" ")
            continue
        match = re.fullmatch(r"([A-Z]+)([012])?", token)
        if not match:
            output.append(_PUNCT.get(token, token))
            continue
        base, stress = match.groups()
        ipa = _ARPA_TO_IPA.get(base)
        if base == "AH" and stress in ("1", "2"):
            ipa = "ʌ"
        if ipa is None:
            output.append(token)
            continue
        if base in _VOWELS and stress in ("1", "2"):
            output.append("ˈ" if stress == "1" else "ˌ")
        output.append(ipa)
    return "".join(output)


def _english_to_ipa(text):
    g2p = _english_g2p()
    output = []
    last = 0
    for match in _LATIN_RUN.finditer(text):
        output.append(text[last:match.start()])
        output.append(_arpabet_to_ipa(g2p(match.group(0))))
        last = match.end()
    output.append(text[last:])
    return re.sub(r" {2,}", " ", "".join(output)).strip()


def phonemize_text(text):
    """Return Nara-vocabulary phoneme symbols for Burmese and English input."""
    if not isinstance(text, str) or not text.strip():
        raise ValueError("ဖတ်ရန် စာသား ထည့်ပါ။")
    normalized = verbalize(text)
    prepared = prepare_names(normalized)
    if _LATIN.search(prepared):
        prepared = _english_to_ipa(prepared)
    result = phonemize(prepared)
    if not result.strip():
        raise ValueError("စာသားမှ အသံထွက်ပြောင်းလဲမှု မရရှိပါ။")
    return result
