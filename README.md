# 📌 Mobile-YOLO-Segmentation-Engine

### 🚀 Proje Özeti
Bu proje; Android işletim sistemine sahip mobil cihazlarda, tamamen **internetsiz (on-device / edge AI)** olarak çalışan, gerçek zamanlı bir YOLO segmentasyon ve nesne tespiti motorudur. Sistem; bulut tabanlı bir sunucuya ihtiyaç duymadan, cihazın kendi donanım kaynaklarını optimize ederek uçtan uca yüksek performanslı bir yapay zeka deneyimi sunar.

### 🎯 Öne Çıkan Teknik Özellikler
* **Çoklu Giriş Desteği:** Canlı kamera akışı (real-time stream), video dosyaları ve yerel fotoğraflar üzerinde kesintisiz segmentasyon gerçekleştirebilir.
* **Modeller Arası Dinamik Geçiş:** Uygulama kapatılmadan veya yeniden başlatılmadan, farklı YOLO segmentasyon modelleri (Örn: YOLOv8-seg, YOLOv11-seg varyasyonları) arasında çalışma anında (runtime) dinamik geçiş yapılabilir.
* **Edge AI & Çevrimdışı Çalışma:** Model, cihaz üzerinde optimize edilerek gömüldüğü için internet bağlantısının olmadığı tamamen izole ortamlarda dahi kararlı bir şekilde çalışır.

### 🛠️ Kullanılan Teknolojiler & Kütüphaneler
* **Ana Programlama Dilleri:** Python (Model eğitimi/dışa aktarım), Java(Android uygulama geliştirme), C++
* **Yapay Zeka & Optimizasyon:** YOLO (Ultralytics), TensorFlow Lite / ONNX Runtime Mobile (Donanım ivmelendirmeli model çalıştırma)
* **Görüntü İşleme:** OpenCV Android SDK / CameraX API

