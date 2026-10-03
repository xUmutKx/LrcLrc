# LrcLrc 2.1 - arama kuralları (notlar)
- Düz yazı = ifade (art arda kelimeler, satır sınırını aşabilir). Virgül = her parça tüm sözlerde herhangi bir yerde (AND).
- Parçada en az bir 3+ harfli kelime gerekir; yalnız kısa kelimelerden oluşan parçalar yok sayılır (durum satırında not). İfade içindeki kısa kelimeler korunur (art arda kalması için).
- Mantık: app/.../SearchLogic.java (Android'siz). Test: test_search/SearchLogicTest.java
  (javac -encoding UTF-8 -d /tmp/o app/src/main/java/com/umutk/lrclrc/SearchLogic.java test_search/SearchLogicTest.java && java -cp /tmp/o SearchLogicTest) -> ALL PASSED
- Song'da transient idxCi/idxCs önbelleği; vurgulama normalize->ham indeks eşlemesiyle (noktalama güvenli); parça başına renk.
- Yardım: çekmece menüsü "Arama kuralları", durum satırına dokunma, arama kutusu altında ipucu.
- Derleme: gradle çevrimdışı AGP 8.7.3 önbellekte yok -> derlenemedi, APK üretilmedi. GitHub Actions ile derlenmeli.
- Push EDİLMEDİ.
