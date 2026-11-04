# LibRawのソース提供とビルド境界

`LibRaw-0.22.3.tar.gz`は公式の未改変アーカイブを保持したもの。
CDDL-1.0を選択して使用し、ソース提供をupstreamサイトの存続だけに依存させない。
対応するAPKのソースcommit/tagから、このファイルとビルド設定・通知を取得できる。
公開前はローカル草案。APKの配布は別承認とし、配布時のcommit/tag・ソース取得案内を確認する。

- 取得元: https://www.libraw.org/data/LibRaw-0.22.3.tar.gz
- upstream tag: `0.22.3`、tag object `75d153244abb2e0cdbe0b0651743a6fefc26eebd`
- tagの実commit: `cf02d66eb5c19d4e46df7aee0582e47a4918509a`
- archive SHA-256: `789f3c33466210a1a0907f747a97c715d21bbf4af712f257fcbbd90111b227e4`
- ソースへのpatchは0。元アーカイブの244ファイルとビルド展開先のbyte一致を確認した。
  upstreamの`.patch`/`.diff`ファイルもそのまま保持しており、こちらで適用した変更ではない。

## ライセンス選択とリンク

upstreamはLGPL-2.1またはCDDL-1.0を選択できる。本構成はCDDL-1.0を選択している。
CDDL 3.1の対象ソース提供・取得案内、3.3の通知保持、3.5/3.6の配布・larger workの条件を確認する。
LGPLで配布する判断へ変える場合は、静的リンクを含む再リンク等の条件を改めて検討する。
JNIが`.so`でも、その内部のLibRawは`add_library(raw STATIC ...)`による静的リンク。

このrepo側の`dng_decoder.cpp`とCMake設定は追加の呼出/ビルドコードで、LibRawファイルの改変ではない。
これを理由に他のライセンス義務がないと断定せず、対象ソースと配布物をレビューする。

`libcinematic_dng.so`はarm64-v8a/x86_64を含む。NDK r29のlibc++/ABI runtimeも静的に入る。
実ELFのDT_NEEDEDは`libz.so, libm.so, libdl.so, libc.so`で、これらはAndroidシステム提供。
`libraw.so`、`libc++_shared.so`、zlibの複製バイナリはAPKに追加しない。
既存のAndroidX/CameraX由来の別JNIライブラリはこの追加とは別に扱う。
静的archiveは`--exclude-libs,ALL`、呼出側はhidden visibility、最終exportは`dng.exports`で限定し、JNI窓口以外へ
LibRaw/C++ runtimeのsymbolsを公開しない。C++のobject/exceptionを別JNI libraryとやり取りしない。

## 同梱通知

APKの`assets/third_party/libraw/`へCDDL全文、COPYRIGHT、DCB BSD全文、upstreamがMITと表示する
Adobe DNG SDK 1.4由来部分のMIT通知、実際のNDK r29 toolchain NOTICE、SOURCE.txtを含める。
NDK NOTICEはtoolchain全体の通知集で、リンクされたruntimeより広い範囲を含む。
X3FTOOLS/外部DNG SDK/JPEG/LCMS/RawSpeed/OpenMP/追加demosaic packsはビルドで有効にしない。
GPL追加packをアーカイブ外から取得・適用しない。実リンク・通知の最終確認は実APKで行う。

## 再ビルド

JDK 25、Android SDK 37.0/build-tools 36.0.0、NDK 29.0.14206865、CMake 3.22.1を用意し、
repoのGradle wrapper 9.8.2で次を実行する。CMakeはこの保持済みアーカイブをhash照合して展開し、
LibRawをネットワークから改めて取得しない。通常のGradle/Android依存取得は別に必要。

```text
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Windowsでは`./gradlew`を`.\gradlew.bat`へ置き換える。build種別・ABI・ソースSHAを記録する。
これは手順の再現性で、署名・ZIP時刻・環境まで含めたAPKのbyte一致を保証しない。
固定署名やRelease公開をこの文書だけで開始しない。
