Bu klasöre aşağıdaki dosyaları kopyalayın:

1. yolo11n-seg.ncnn.param   ← model_export/model_export.py ile oluşturulur
2. yolo11n-seg.ncnn.bin

Opsiyonel (YOLO26):
3. yolo26n-seg.ncnn.param
4. yolo26n-seg.ncnn.bin

ModelManager.java, bu klasördeki tüm .ncnn.param/.bin çiftlerini
otomatik olarak algılar ve uygulamanın spinner'ında listeler.
