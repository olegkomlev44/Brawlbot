# Обучение YOLO26 для Colt Bot (запускать в Google Colab, GPU: Runtime -> Change runtime type -> T4).
#
# 1) Включи в приложении «Записывать кадры и действия», сыграй 10-20 матчей.
#    В Загрузки/Brawlbot/ появятся папки rec_*: в каждой кадры f*.jpg и разметка f*.txt (бот размечает кадры сам, грубо).
# 2) Закинь эти папки в Colab (Files -> upload) или на Google Drive, путь укажи в SRC ниже.
# 3) Запусти ячейки по порядку. В конце скачается yolo26.tflite - загрузи его в приложении кнопкой «Загрузить модель».
#
# !pip install -U ultralytics ai-edge-litert

import glob, os, random, shutil

SRC = "/content/rec"            # здесь лежат папки rec_* (можно вложенные)
OUT = "/content/ds"
NAMES = ["me", "enemy", "ally", "box", "cube", "bullet"]   # порядок как в приложении (Cls.NAMES)
EPOCHS = 80

# --- собираем датасет: кадры, у которых есть .txt ---
pairs = []
for jpg in glob.glob(os.path.join(SRC, "**", "f*.jpg"), recursive=True):
    txt = jpg[:-4] + ".txt"
    if os.path.exists(txt):
        pairs.append((jpg, txt))
random.seed(0)
random.shuffle(pairs)
print("кадров с разметкой:", len(pairs))
assert len(pairs) > 200, "мало кадров: сыграй ещё несколько матчей с включённой записью"

shutil.rmtree(OUT, ignore_errors=True)
for part in ("train", "val"):
    os.makedirs(f"{OUT}/images/{part}", exist_ok=True)
    os.makedirs(f"{OUT}/labels/{part}", exist_ok=True)
nval = max(20, len(pairs) // 10)
for i, (jpg, txt) in enumerate(pairs):
    part = "val" if i < nval else "train"
    name = f"{i:06d}"
    shutil.copy(jpg, f"{OUT}/images/{part}/{name}.jpg")
    shutil.copy(txt, f"{OUT}/labels/{part}/{name}.txt")

with open(f"{OUT}/data.yaml", "w") as f:
    f.write(f"path: {OUT}\ntrain: images/train\nval: images/val\nnames:\n")
    for i, n in enumerate(NAMES):
        f.write(f"  {i}: {n}\n")

# --- обучение ---
from ultralytics import YOLO

model = YOLO("yolo26n.pt")           # nano: самая быстрая на телефоне
model.train(
    data=f"{OUT}/data.yaml", imgsz=704, epochs=EPOCHS, batch=16, patience=20,
    project="/content/runs", name="bs", exist_ok=True,
    fliplr=0.5, hsv_h=0.01, hsv_s=0.4, hsv_v=0.3,   # слабая цветовая аугментация: цвета в игре важны
)

# --- экспорт в LiteRT (.tflite): вход 320x704 float32, сквозной выход без NMS ---
best = YOLO("/content/runs/bs/weights/best.pt")
try:
    best.export(format="tflite", imgsz=(320, 704), nms=False)
except Exception as e:
    print("экспорт tflite не удался:", e)
    print("обнови ultralytics (pip install -U ultralytics) и повтори только эту ячейку")
    raise

tfl = sorted(glob.glob("/content/runs/bs/weights/**/*.tflite", recursive=True), key=os.path.getsize)
print("найденные .tflite:", tfl)
final = [p for p in tfl if "float32" in p] or tfl
shutil.copy(final[-1], "/content/yolo26.tflite")

# --- проверка формы входа/выхода: ждём вход [1,320,704,3], выход [1,300,6] (или [1,10,4620]) ---
from ai_edge_litert.interpreter import Interpreter
it = Interpreter(model_path="/content/yolo26.tflite")
it.allocate_tensors()
print("вход :", it.get_input_details()[0]["shape"], it.get_input_details()[0]["dtype"])
print("выход:", it.get_output_details()[0]["shape"])

try:
    from google.colab import files
    files.download("/content/yolo26.tflite")
except Exception:
    print("модель: /content/yolo26.tflite")
