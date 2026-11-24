# Vortex Music — Teknik Doküman ve Geliştirme Notları

Son güncelleme: 24 Kasım 2026 · Sürüm: Beta 2 (test aşamasında)
Paket: `io.github.vortexmusic.player` · Lisans: GPL-3.0 · Marka: Quiet Apps

## 1. Proje özeti
İnternetsiz (INTERNET izni yok), reklamsız, takipsiz, açık kaynaklı Android müzik çalar.
Yerel müzik dosyalarını çalar. Kendi ses işleme motoru vardır ("Vortex Audio Engine").
Arayüz: Kotlin + Android Views (Compose kullanılmıyor). Geliştirme: telefonda, CodeAssist ile.

## 2. Teknoloji
- Dil: Kotlin · UI: Views (kod ile çizilen arayüz)
- Çalma: Media3 / ExoPlayer, `MediaSessionService` (bildirim, kilit ekranı, Bluetooth kumandalar)
- Ses işleme: Media3 `BaseAudioProcessor` (kendi yazdığımız DSP)
- Veri: şarkılar `MediaStore`'dan okunur; ayarlar ve istatistikler `SharedPreferences`
- Veritabanı (Room) yok. Beta 3'te not / işaret / şarkıya özel EQ için düşünülüyor.
- Formatlar: Android'in kendi çözücüleri (MP3, AAC, FLAC, OGG, Opus, WAV, M4A). Cihaza göre değişir.

## 3. Dosyalar ve görevleri
| Dosya | Görevi |
|---|---|
| MainActivity.kt | Ana ekran, sekmeler, kütüphane listeleri, oynatıcı ekranı, mini oynatıcı |
| Engine.kt | `PlaybackService` (çalma servisi), `AudioFxController` (ses efektleri, hazır ayarlar, kayıt) |
| VortexEq.kt | Ses motoru: EQ, bas, stereo genişlik, ses yükseltme, sınırlayıcı, otomatik dengeleme, ses seviyesi, bas vuruşu, spektrum |
| AudioScreen.kt / AudioControls.kt | Ses ekranı (EQ grafiği, efekt kutuları, profiller, çıkış cihazı) Dosya sonundaki özel görünümler: `EffectTileView` (dolgu kutuları; yazı iki kez çizilir ve dolgu sınırında kırpılır, dolgunun üstünde beyaz, dışında normal renk), `LockButtonView` (yuvarlak asma kilit, kilitliyken mor), `PureCardView` (Saf mod kartı: dalgalı çizgi dümdüz olur, kayan anahtar). Kilitleyince içerik %40 saydamlığa solar (`LockLayout` dokunuşu da engeller). Profil seçenekleri `SheetDialog` kullanır |
| VortexRing.kt | Kapak çevresindeki girdap animasyonu (8 stil) ve ayarları |
| CoverGestures.kt | Oynatıcı ekranı hareketleri: kapağı yana çekince şarkı değişir, ekranı aşağı çekince kapanır |
| ColorUtil.kt | Albüm kapağından baskın rengi bulur |
| StartupAnim.kt | Açılış animasyonu |
| ArtistGroups.kt | Sanatçı gruplama, çoklu sanatçı ayırma, alfabetik sıra |
| SmartSort.kt | Akıllı sıralama ("The" atlama, sayıları doğru sıralama) |
| ListOptions.kt | Liste sıralama ve görünüm seçenekleri |
| Library.kt, Search.kt | Akıllı listeler ve arama |
| Queue.kt | Kuyruk ekranı: kapaklı satırlar, yana kaydırarak kaldırma, basılı tutup sürükleme (otomatik kaydırmalı), kayarak açılma/kapanma animasyonu, üstte kapatma tutamacı |
| BottomSheet.kt | `SheetDialog`: Material 3 tarzı alt sayfa (tek seçim, liste, Kaydet düğmeli çoklu seçim). Çoğu seçimde `AlertDialog` yerine kullanılır |
| DialogStyle.kt, res/values/styles.xml | Kalan `AlertDialog`'lar için yuvarlak Material 3 stili (`VortexDialog`, `VortexDialogLight`) |
| SettingsScreen.kt | Ayarlar ekranı (gruplu kartlar, tonlu simge daireleri); başlığa 7 kez dokunmak gizli geliştirici araçlarını açar |
| AudioTest.kt | `Headroom` (Bluetooth'ta sabit -2 dB; 24 Kasım'a kadar -5'ti), `HeadroomProcessor` ve gizli ses çıkışı test aracı |
| ChipButton.kt | `Ui.chipButton()`: küçük, çerçeveli Material 3 tarzı düğme, isteğe bağlı `SortGlyph` simgesi (kısalan üç çizgi). Liste başlıklarında "Sırala" ve kapak düğmeleri için kullanılır; satır sağa yaslıdır (başparmak erişimi) |
| PrivateLibrary.kt | Vortex klasörü: kullanıcının seçtiği klasör (Storage Access Framework ağacı), içine `.nomedia` konur; tarar, bilgileri önbelleğe alır, şarkı kopyalar. Buradaki şarkılar negatif id ve `docUri` kullanır. Tarama menüsü (`scanMenu`): elle "şimdi tara" ve otomatik tarama anahtarı (`auto`, varsayılan açık); hızlı ayarlar ve Ayarlar'dan açılır |
| MiniPlayerBg.kt, NavLabels.kt, WaveBarsDrawable.kt | Mini oynatıcı ilerleme dolgusu, alt çubuk yazıları, dalga çubuğu |
| StatsScreen.kt | İstatistik ekranı |
| Backup.kt | Yedekleme ve geri yükleme |
| Duplicates.kt | Kopya şarkı bulma |
| Lyrics.kt | Şarkı sözleri (şu an kapalı: `ENABLED = false`) |
| Pages.kt | Ana sayfa, araçlar, "İndirilenler" yer tutucusu (kapalı) |
| Theme.kt, Ui.kt | Tema, renkler, çizim yardımcıları |
| Visuals.kt | `NeonBackgroundDrawable` (neon zemin: degrade, ışık lekeleri, parlayan çizgiler) ve `IconDrawable` (kodla çizilen 20 simge: ev, arama, nota, kalp, oynat, karıştır, EQ vb.). Resim dosyası kullanılmaz, simgeler vektör gibi çizilir |
| I18n.kt | Dil desteği: `tx("tr", "en")` |
| Models.kt | `Song` veri sınıfı: id, başlık, sanatçı, albüm, eklenme, süre, yıl, parça no, değişim zamanı, dosya adı, yol. `uri` MediaStore'dan, Vortex klasöründeki şarkılar için `docUri`'den üretilir |
| Tools.kt | Araçlar kaydı. `ToolEntry` (başlık, alt başlık, ikon, eylem) ve `ToolHost` (activity, ui, şarkı listesi, veriyi yenile, servisi yeniden başlat). Yeni araç eklemek: `install()` ve `entries()` içine birer satır. Şu an: ThemeTool, BackupTool, DuplicatesTool, LyricsTool, AudioTestTool (gizli), PrivateLibrary |
| AlbumArt.kt | Kullanıcının kendi eklediği albüm/sanatçı kapakları. Uygulamanın özel klasörüne (`filesDir/albumart`) JPEG olarak kaydedilir (en fazla ~900 px), anahtarlar `vortex_art` ayarında. Önbellek (LruCache) + tek iş parçacığı. `keyFor()` önce albüm, "sanatçıya göre" açıksa sanatçı kapağını seçer |
| Covers.kt | Şarkı kapağı yükleme: önce önbellek, yoksa arka planda (2 iş parçacığı) Android 10+ `loadThumbnail`, olmazsa dosyadaki gömülü resim (`MediaMetadataRetriever`). Bulunamayanlar `missing` listesinde tutulur (tekrar denenmez) |

## 4. Ses akışı (en önemli kısım)
Şarkı dosyası → ExoPlayer (çözme) → **VortexEqProcessor** → ses çıkışı

VortexEqProcessor içinde sırayla:
1. 10 bantlı EQ (RBJ peaking biquad, Q=1.41) · frekanslar: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k
2. Harmonik bas güçlendirici
3. Stereo genişlik (mid/side)
4. Bas koruyucu: sadece 140 Hz altını izler, çok yükselirse (eşik 0.40) o bandı kısar, tizlere dokunmaz (kulaklıkta sert bas vuruşu için)
5. Kazanç: ön kazanç (EQ tepe artışının %75'ini dengeler) + ses yükseltme + otomatik ses dengeleme (yavaş: hedef −18 dBFS, en çok −6 dB kısar, +2 dB açar; kısarken 1,5 sn, açarken 4 sn)
6. İleriye bakan sınırlayıcı (limiter, 3 ms önden bakış, tavan 0.95) · bozulmayı önler

Motordan sonra `HeadroomProcessor`, **çıkış Bluetooth ise** seviyeyi sabit **-2 dB** düşürür (kablolu ve hoparlörde 0 dB). 24 Kasım'a kadar -5 dB'ydi; Poweramp ve YouTube Music'in yanında Vortex kısık ve "yavan" geldiği için azaltıldı. Saf moddan bağımsızdır, yani Bluetooth'ta Saf mod bile dosyadan 2 dB düşüktür. Süzgeç değil, sabit kazanç değişimidir. Hangi değerin berraklığı artırdığı kanıtlanmış değil (bölüm 7).

**Saf mod** (`pure`) EQ, bas, genişlik, ses yükseltme ve otomatik dengelemeyi devre dışı bırakır. **EQ kilidi** (`eqlock`) ses ekranındaki kaydırıcılara dokunmayı engeller.
Şarkı atlayınca / değişince (`onFlush`) tüm filtre ve sınırlayıcı belleği temizlenir (tık sesini önler).
Parametreler 20 ms yumuşatılır (tıkırtıyı önlemek için).

Ses işlemcisi ayrıca animasyonlar için ölçer: `level` (seviye), `beatCount` (bas vuruşu), `spectrum` (32 bant, 1024 noktalı FFT). Mikrofon izni gerekmez.

## 5. Kayıtlı ayarlar (SharedPreferences)
- `vortex_artist`: `sep` (sanatçı ayırıcı bitleri), `smart` (akıllı sıralama)
- `vortex_ring`: `style` (0-9; seçicide 0 Kapalı, 1 Halka, 9 Vortex, 2 Girdap, 5 Nefes, 8 Damla görünür; gizli: 3, 4, 6, 7 Halka'ya döner), `color` (0 albüm rengi, 1 Vortex rengi, 2 gökkuşağı)
- `vortex_audio`: `pure`, `eqlock`, `headroom_off` (sadece test), `devtools` (gizli araçlar açık)
- `vortex_private`: `tree` (seçilen klasör adresi), `only` (sadece Vortex klasörünü göster; açıkken Tüm şarkılar boş kalır), `auto` (otomatik tarama, varsayılan açık), `cache` (taranan şarkı bilgileri)
- Ses efektleri: `levels10` (10 bant), `enabled`, `autolevel`, profiller
- `vortex_stats`: dinleme sayıları ve son dinleme zamanları

## 6. Sürümleme ve yayın
- Beta 1: yayınlandı (6 Kasım)
- Beta 2: 14 Kasım itibarıyla 3 haftalık test, sonra ~1 hafta düzeltme, sonra yayın
- Beta 3: yeni özellikler. Sonra final sürüm.
- Her yeni sürümde `versionCode` bir öncekinden büyük olmalı (yoksa güncelleme yüklenmez)
- İmzalama anahtarı (keystore) ve şifresi ASLA GitHub'a konmaz

## 7. Bilinen riskler ve yapılacaklar
- `loadSongs()` ana işlemde çalışıyor: büyük kütüphanede kısa donma riski. Arka plana alınacak.
- Bluetooth kulaklıkta animasyonlar sesten biraz önce hareket edebilir (gecikme ölçülmüyor).
- **MainActivity.kt 100.000 karakteri aşınca telefonda yapıştırırken kesiliyor** (14-15 Kas'te yaşandı). 95.000 altında tut; büyüdükçe parçala (Beta 3).
- Covers.kt: bulunamayan kapaklar oturum boyunca tekrar denenmez (`missing` listesi). Kapak sonradan eklenirse uygulamayı yeniden açmak gerekir.
- `onFlush()` imzası Media3 sürümüne göre değişebilir (derleme hatası olursa düzeltilir).
- Vortex klasörü: 22 Kasım'da çalma, kapaklar ve tarama çalışıyor diye bildirildi. Buradaki şarkılar Tüm şarkılar, Sanatçılar, Albümler ve albüm/sanatçı detay listelerinde görünmez (`libSongs`), kendi listesinde görünür (`libView = 12`); Beğenilenler, arama ve kuyruk yine kapsar. Id'leri negatif olduğu için `id < 0` kontrolü "şarkı yok" anlamına gelemez (beğeni düğmesinde 24 Kasım'a kadar bu hata vardı; `byId.containsKey(id)` kullan). İlk not: 21-22 Kasım'da yazıldı. Tarama arka planda çalışır; çok büyük klasörde ilk seferde biraz sürebilir. Şarkı kopyalamak yeri iki katına çıkarır (orijinaller kalır). Android `Download` ya da `Android/data` klasörünün seçilmesine izin vermez.
- Bluetooth ses payı bir varsayım: işlemci hattan çıkarılınca da "baget ayrıntısı ezilir" etkisi sürdü, yani sebep hâlâ açık. 23 Kasım'da sahibi Vortex'i Bluetooth'ta Poweramp ve YouTube Music ile karşılaştırdı, Vortex'i "yavan" ve daha kısık buldu. `VortexEq.kt` kod incelemesinde belirgin bir hata görülmedi (çift hassasiyetli biquad'lar, ileriye bakan sınırlayıcı, efektler kapalıyken dokunulmadan geçiş); bu yüzden ilk düzeltme ses payını -2 dB'ye indirmek oldu (24 Kasım, kulakla henüz doğrulanmadı). Yapılacaklar: aynı ses seviyesinde kör A/B (0 / -2 / -5 dB), Saf modda 0 dB ses payı, daha yumuşak varsayılan bir "Vortex karakteri" ayarı, gizli ses çıkışı testiyle dosya biçimine bakmak.
- Kitaplık görünüm numaraları (`libView`) birçok ekranda ortaktır: 0 kitaplık, 1 beğenilenler, 2 sanatçılar, 3 sanatçı detayı, 4 albümler, 5 albüm detayı, 6 klasörler, 7 klasör detayı, 8 akıllı listeler, 9 akıllı liste detayı, 10 tüm şarkılar, 11 araçlar, 12 Vortex klasörü. Yeni numara eklemeden önce listeye bak (23 Kasım'da çakışma Vortex klasörü yerine Ayarlar'ı açtı).
- Açık tema (Kar Beyazı) bazı yerlerde düşük kontrastlı (dalga çubuğu zemini, bazı simgeler).
- Kulaklık profilleri: AutoEq verisi sahiplik ve lisans sebebiyle uygulamaya gömülmüyor.

## 8. Beta 3 fikirleri
Otomatik yedekleme (telefon sıfırlanırsa ayarlar kaybolmasın; Android Auto Backup kuralları ya da düzenli yedek dosyası) · Oynatıcıdan sıra listesi (aşağıdan yukarı çekince) · MainActivity.kt'yi parçalara bölme · Şarkıya özel EQ hafızası · Vortex Özeti (yıl sonu özeti, telefonda) · bölüm işaretleri ve kesme noktaları · dinleme sağlığı hatırlatıcısı · Girdap Karışık · şarkı notları · parametrik EQ · ReplayGain etiketleri · çapraz geçiş · Room veritabanı.

### Beta 3 notları (Beta 2 test süresince yeni özellik EKLENMEZ)
- Behruz Hoca geri bildirimi (16 Kas): (1) mini oynatıcıyı alttan yukarı çekerek oynatıcı ekranını açma (CoverGestures'a eklenir, kapatma hareketinin tersi); (2) tüm ayarlar için görünür bir ayarlar düğmesi (dişli simgesi). Madde 2 keşfedilebilirlik sorunu olduğu için düzeltme haftasına alınabilir.
- Kulaklıkta bas fazla vuruyor: düzeltme (Kulak üstü/içi) açıkken bas güçlendiriciyi otomatik kısma (basların üst üste binmesini önleme) düşünülüyor, test sonucu bekleniyor
- Oynatıcı ekranında üstte tam genişlik canlı spektrum
- Hap şeklinde yuvarlak düğmeler (oynatıcı ekranı), kapak boyutunu ekrana göre büyütme
- Gerçek ses dalgası çubuğu (şu an süs deseni; dosyayı çözerek çıkarılabilir, yavaş)
- Kulaklık sihirbazı: ilk açılışta "Kulaklığın nasıl?" sorusu, cevaba göre düzeltme otomatik
- Berraklık araştırması: Poweramp ile aynı ses seviyesinde karşılaştırma (efektler kapalı), gerekirse harmonik bas ve ön kazancı yumuşat
- Otomatik yedekleme, oynatıcıdan sıra listesi, şarkıya özel EQ, Vortex Özeti, parametrik EQ, çapraz geçiş, Room, gerçek Bluetooth gecikme ölçümü
- Test geri bildirimi (Behruz Hoca, 16 Kas): mini karttan yukarı çekerek oynatıcıyı açma ve ana ekranda ayarlar (dişli) düğmesi: YAPILDI (kullanım kolaylığı düzeltmesi sayıldı)
- Bas ağırlığı: kulaklık düzeltme + bas güçlendirici + hazır ayar basları üst üste biniyor; düzeltme açıkken bas güçlendiriciyi otomatik kısmayı düşün (kullanıcı testinin sonucuna göre)
- Alt menü: Araçlar sekmesi için yer (şu an 3 sekme + gizli 4.)

## 9. Geliştirme günlüğü
- 6 Kas: Beta 1 yayınlandı.
- 8 Kas: İngilizce arayüz eklendi (dil seçeneği: Türkçe / English / Sistem).
- 13 Kas: Kendi 10 bantlı EQ ve ses motoru. Hazır ayarlar yeniden düzenlendi. Dinleme geri bildirimi: bas zayıf, ses yükseltmede tıkırtı → yumuşatma ve sınırlayıcı eklendi.
- 15 Kas: Kulaklıkta bas sert vuruyor geri bildirimi → bas koruyucu eklendi, ön kazanç dengelemesi %50'den %75'e çıkarıldı, Enstrümantal ve Derin Bas hazır ayarları yeniden dengelendi. Otomatik dengeleme yumuşatıldı, şarkı değişiminde bellek temizliği eklendi (başlangıç tıkını önler). Kapağı yana çekince şarkı değişir (ve çalar), ekranı aşağı çekince kapanır. MainActivity.kt parçalandı (CoverGestures.kt, ColorUtil.kt).
- 15 Kas (akşam): Şarkı başı kesmesinin gerçek nedeni bulundu: `Fader` (Queue.kt) "şarkı değişti" olayında sesi 0'a çekiyordu, ama bu olay sesin başlamasından sonra geliyor, bu yüzden başlangıç kesiliyordu. Fader artık sesi 0'a çekmiyor; yumuşak başlangıç ses motorunda (VortexEq, 12 ms yükseliş, örnek hassasiyetinde). Bas koruyucu belleği de atlamada temizleniyor. EQ grafiğine canlı spektrum ve neon parlama eklendi. Animasyon senkronu: görsel değerler çıkış gecikmesi kadar geriden okunur (hoparlör 110, kablolu 90, Bluetooth 280 ms taban tahmini + kullanıcı ince ayarı ±). Not: Bluetooth gecikmeleri ölçülmedi, tahmindir. Açık sorun: kulaklıkta berraklık Poweramp kadar değil (neden araştırılıyor).
- 15 Kas (gece): Arayüz cilası: alt menüye yazılar (NavLabels.kt), mini oynatıcı kartı ilerlemeyle doluyor (MiniPlayerBg.kt), oynatıcıda dalga çubuğu (WaveBarsDrawable.kt, süs deseni), Animasyon senkronu kutusu (Otomatik/Elle/Kapalı) yanıp sönerek durumu gösteriyor. Bundan sonra Beta 2 testi bitene kadar yeni özellik yok.
- 16 Kas: Behruz Hoca önerisi: mini karttan yukarı çekince oynatıcı açılır (CoverGestures.attachMini), ana ekran üst satırına ayarlar dişlisi eklendi (IconDrawable.GEAR). Ses motorundaki kesmeler giderildi (kullanıcı onayı). Kalan: kulaklıkta bas sert (ayar testi bekleniyor).
- 14 Kas: Efekt kutuları arayüzü, otomatik ses dengeleme, sanatçı arama/gruplama/fotoğraf, sanatçı ayırıcı ayarları, akıllı sıralama, girdap animasyonu (8 stil), bas vuruşu ve spektrum.
- 17-18 Kas: Ayarlar ekranı gruplu kartlarla; Kuyruk yenilendi (kapaklı satırlar, yana kaydırarak kaldırma, basılı tutup sürükleme, otomatik kaydırma).
- 19-20 Kas: Saf mod, EQ kilidi, ses çıkışı test aracı (sonra gizlendi). Testlerde -9 dB daha net ama çok kısık, -6 kabul edilebilir; son seçim sadece Bluetooth'ta -5 dB.
- 21 Kas: Material 3 tarzı (kütüphanesiz): tonlu yüzeyler, medium yazı ağırlığı, alt sayfalar (`SheetDialog`), yuvarlak pencereler, yeni alt çubuk, kuyruk oynatıcının altındaki tutamaçtan açılır. Halkalar: Damla (yeni), spiral Girdap, gökkuşağı rengi; Nabız, Yörünge, Ritim ve Spektrum gizlendi. Temalar Adaçayı / Gece Siyahı / Kar Beyazı'na indi. `skipNext()` kuyrukta tek şarkı varken tüm şarkıları yükler. `MainActivity.kt` ~100 bin karakteri aşınca yapıştırmada sonu kesildi (`REQ_ALBUM_ART` derleme hatası); yorumlar, boş satırlar ve fazla girinti silinerek ~80 bine indirildi.
- 22 Kas: Oynatıcıdaki dişli düğmesinden hızlı ayarlar sayfası. Çoklu seçimli alt sayfa (sanatçı ayırıcıları). Ekolayzır ekranı yenilendi (tonlu kartlar, hap seçenekler, yuvarlak efekt kutuları, neon parlamasız sade grafik çizgisi). Vortex klasörü (`PrivateLibrary.kt`). Kuyruk için yumuşak kayma animasyonu ve kapatma tutamacı. Yeni açılış animasyonu (harfler V'nin içine çekilir).
- 22 Kas (devam): Oynatıcı ekranı düzeni `activity_main.xml` içinde yeniden kuruldu: `npTitleRow` (başlık, sanatçı, beğeni kalbi), geri / oynat / ileri çevresinde Karıştır ve Döngü hücreleri olan kontrol satırı. Klasör ve Hız hücreleri gizli (`visibility=gone`) olarak duruyor ki mevcut tıklama kodları bağlanmaya devam etsin; eylemleri hızlı ayarlar sayfasına taşındı (`speedSheet()`, klasöre ekle). Hızlı ayarlar altı maddeye indi. Artık sadece arka plan gradyanı kapak rengini alıyor; oynat düğmesi (`neonGradient(false, 40)`), dalga çubuğu ve tutamacı kontrast ve kimlik için Vortex moru kalıyor. Şarkı satırlarındaki beğeni kalbi dokunulabilir (`toggleFavoriteId`).

- 22 Kas (akşam): Kitaplık taraması seçenekleri (elle ve otomatik) hızlı ayarlara ve Ayarlar'a eklendi.
- 23-24 Kas: Vortex klasörü kendi satırına taşındı (`libView = 12`), Tüm şarkılar, Sanatçılar ve Albümler'den ayrıldı. Efekt kutusu yazı okunurluğu düzeltildi. Profil menüsü alt sayfa oldu. Yeni kilit düğmesi (ses ekranını soldurur) ve Saf mod kartı. Sağda küçük çerçeveli "Sırala" düğmesi (`ChipButton.kt`). Düzeltildi: beğeni kalbi Vortex klasörü şarkılarında çalışmıyordu (negatif id); oynatıcı üst simgeleri artık hep Vortex moru. Poweramp ve YouTube Music ile ses karşılaştırması: Vortex yavan ve kısık geldi, bu yüzden Bluetooth ses payı -5'ten -2 dB'ye indi. README artık ses motorunun hâlâ geliştirildiğini söylüyor ve geri bildirim istiyor.

## 9b. Yayın öncesi: yabancı geliştiriciler için
- Kod yorumları ve bu doküman Türkçe. Yayından önce (düzeltme haftası) kod yorumları İngilizceye çevrilecek, README İngilizce yazılacak.
- Sınıf, fonksiyon ve değişken adları zaten İngilizce.

## 10. Çalışma kuralları (kendime notlar)
- Dosya değiştirirken eskisini komple silip yenisini yapıştır (altına ekleme: "Redeclaration" hatası verir).
- Yeni özellik eklemeden önce kodu GitHub'a yükle ve yedek al.
- Yayın öncesi son haftada büyük özellik ekleme, sadece düzeltme.
- Ses ayarlarını geri bildirim olmadan değiştirme.
- Bu dosyayı her büyük değişiklikten sonra güncelle.
- Yeni görünüm numarası eklemeden önce mevcut `libView` değerlerini ara.
- `id < 0` kontrolünü "şarkı yok" anlamında kullanma; Vortex klasörü şarkılarının id'si negatiftir.
