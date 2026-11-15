# ပြင်ပအရင်းအမြစ်နှင့် လိုင်စင်ဆိုင်ရာ အသိပေးချက်

## Nara Mini v1 ရဲ့ မြန်မာစာပြင်ဆင်မှုနှင့် အသံမော်ဒယ်

`app/src/main/python/frontend/` ဖိုင်တွဲထဲက Python စာသားပြင်ဆင်ရေးဖိုင်များနဲ့ ၎င်းတို့သုံးတဲ့ TSV စည်းမျဉ်း၊ အဘိဓာန်ဖိုင်များကို `YeeNyunn/nara-mini-v1` public repository မှ ယူထားပါတယ်။ Apache License 2.0 ရဲ့ စည်းကမ်းချက်များအောက်တွင် ဖြန့်ဝေထားပြီး မူရင်းလိုင်စင်နဲ့ အသိပေးချက်ကို `third_party/nara-mini/` ဖိုင်တွဲထဲမှာ ထည့်သွင်းထားပါတယ်။

မူရင်းရင်းမြစ်: https://huggingface.co/YeeNyunn/nara-mini-v1

App က ONNX model နဲ့ `voice.json` ဖိုင်ကို Hugging Face repository ရဲ့ သတ်မှတ်ထားတဲ့ revision မှ ဒေါင်းလုဒ်လုပ်ပါတယ်။ အရွယ်အစားကြီးတဲ့ model ဖိုင်တွေကို ဒီ GitHub repository ထဲမှာ မထည့်ထားပါ။ လိုင်စင်၊ မူပိုင်ခွင့်အသိပေးချက်နဲ့ SHA-256 စာရင်းကို မူရင်း repository နဲ့ `third_party/nara-mini/` ထဲက ဖိုင်တွေမှာ ကြည့်နိုင်ပါတယ်။

## Android အတွက် ONNX Runtime

App က `com.microsoft.onnxruntime:onnxruntime-android` Maven dependency ကို သုံးပါတယ်။ ၎င်းရဲ့ လိုင်စင်နဲ့ အသိပေးချက်ကို https://onnxruntime.ai/docs/install/ မှာ ကြည့်နိုင်ပါတယ်။
