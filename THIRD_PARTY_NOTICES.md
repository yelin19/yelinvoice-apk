# ပြင်ပအရင်းအမြစ်နှင့် လိုင်စင်ဆိုင်ရာ အသိပေးချက်

## Nara Mini v1 မြန်မာစာပြင်ဆင်မှုနှင့် voice model

`app/src/main/python/frontend/` အတွင်းရှိ Nara Mini မြန်မာစာပြင်ဆင်မှုဖိုင်များ၊ စည်းမျဉ်းများနှင့် ဆက်စပ်စာသားဖိုင်များကို `YeeNyunn/nara-mini-v1` မှ ယူထားပြီး Apache License 2.0 အောက်တွင် ဖြန့်ဝေထားသည်။ မူရင်း `LICENSE` နှင့် `NOTICE` ကို `third_party/nara-mini/` ထဲတွင် ထည့်ထားသည်။

မူရင်း model card: https://huggingface.co/YeeNyunn/nara-mini-v1

ONNX voice pack များကို app ထဲတွင် တိုက်ရိုက်မထည့်ထားပါ။ App သည် `yelin201875/Yelinvoice` repository ၏ သတ်မှတ်ထားသော revision မှ encoder, decoder နှင့် `voice.json` ဖိုင်များကို အသုံးပြုသူရွေးချယ်သည့်အခါ ဒေါင်းလုဒ်လုပ်သည်။ Model file များ၏ checksum ကို `app/src/main/assets/voice_packs.json` ထဲတွင် မှတ်တမ်းတင်ထားသည်။ Model ၏ မူပိုင်ခွင့်နှင့် အသုံးပြုမှုစည်းကမ်းများအတွက် Hugging Face repository ၏ လက်ရှိ license/card ကို စစ်ဆေးပါ။

ရင်းမြစ်: https://huggingface.co/yelin201875/Yelinvoice

## English G2P နှင့် NLTK အချက်အလက်

`g2p-en` 2.1.0 ကို Apache License 2.0 အောက်တွင် အသုံးပြုပြီး မူရင်း license စာသားကို `third_party/english-g2p/g2p-en-LICENSE.txt` ထဲတွင် ထည့်ထားသည်။ NLTK 3.8.1 သည် Apache License 2.0 ဖြစ်ပြီး `third_party/english-g2p/nltk-LICENSE.txt` ထဲတွင် ထည့်ထားသည်။ Runtime package များကို Chaquopy မှ build အချိန်တွင် ရယူသည်။

App နှင့်အတူ ထည့်သွင်းထားသော `cmudict.zip` တွင် Carnegie Mellon University ၏ Pronouncing Dictionary ပါဝင်သည်။ မူပိုင်ခွင့်အသိပေးချက်မှာ Carnegie Mellon University, 1993–2008 ဖြစ်သည်။ NLTK data package index တွင် သုတေသနနှင့် စီးပွားရေးရည်ရွယ်ချက်အတွက် အသုံးပြုမှုကို ကန့်သတ်မထားကြောင်း ဖော်ပြထားပြီး မူရင်းကို အသိအမှတ်ပြုဖော်ပြရန် တောင်းဆိုထားသည်။ ဖိုင်အတွင်းရှိ `README` ကို `third_party/english-g2p/CMUdict_README.txt` တွင် ထည့်ထားသည်။

`averaged_perceptron_tagger.zip` သည် English tagger data ဖြစ်ပြီး NLTK data package index အရ MIT License ဖြစ်သည်။ မူရင်း tagger license စာသားကို `third_party/english-g2p/MIT-averaged-perceptron-tagger.txt` တွင် ထည့်ထားသည်။

ရင်းမြစ်များ: https://pypi.org/project/g2p-en/ ; https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/cmudict.xml ; https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/taggers/averaged_perceptron_tagger.xml ; https://github.com/sloria/textblob-aptagger

## Android runtime နှင့် UI library

- `com.microsoft.onnxruntime:onnxruntime-android` ဖြင့် ONNX inference လုပ်သည်။ License/notice: https://onnxruntime.ai/docs/install/
- `com.google.android.material:material` နှင့် AndroidX Core ကို UI၊ Material Design 3 component နှင့် FileProvider အတွက် အသုံးပြုသည်။ ၎င်းတို့၏ Apache License 2.0 သတိပေးချက်ကို dependency နှင့်အတူ ဖြန့်ဝေပါသည်။

## Voice အသုံးပြုမှုနှင့် ပုဂ္ဂိုလ်ရေးခွင့်

Voice pack တစ်ခုကို app ထဲသို့ ထည့်မည် သို့မဟုတ် ဖြန့်ဝေမည်ဆိုလျှင် model၊ training material နှင့် အသံပိုင်ရှင်တို့၏ ခွင့်ပြုချက်ကို သီးခြားစစ်ဆေးပါ။ App သည် voice model training မလုပ်ပါ။
