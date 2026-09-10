from transformers import pipeline

pipe = pipeline("text-generation", model="openbmb/MiniCPM5-2B-GGUF")
messages = [
    {"role": "user", "content": "Who are you?"},
]
pipe(messages)