# WheelLog Шлем (CJ-Helmet)

Форк [WheelLog](https://github.com/Wheellog/Wheellog.Android): приложение подключается к моноколесу
Sherman L, по скорости, току и наклону понимает разгон, торможение и поворот и управляет светом
на шлемах и на самом колесе. В этом же репозитории - прошивка плат ESP32 (папка `cj-helmet/`).

Fork of WheelLog: helmet and wheel lighting for Sherman L - brake, acceleration and turn signals
on ESP32 boards in helmets and on the wheel, plus ZENGGE (LEDnetWF) Bluetooth LED controllers.

**Шаги проекта** (метки `cj-*`, подробно - [cj-helmet/STEPS.md](cj-helmet/STEPS.md)):

1. `cj-1-shlem` - шлем со стоп-сигналом: плата в шлеме, модуль «Шлем» в приложении.
2. `cj-2-svet-koleso` - свет колеса: роли плат «шлем» и «колесо», связь ESP-NOW, раздел «Подсветка».
3. `cj-3-zengge` - дополнительный контроллер ZENGGE на втором шлеме.
4. `cj-4-provod` - провод: плата читает Bluetooth-модуль колеса напрямую.

**Скачать APK:** вкладка [Actions](https://github.com/cjgun/WheelLog-Helmet/actions) - последняя
сборка `CJ Helmet APK`, файл в разделе Artifacts; версии с метками `cj-*` - в Releases.
Приложение ставится рядом с WheelLog из Play (свой applicationId `com.cooper.wheellog.helmet`).

Ниже - исходный README WheelLog.

---

# WheelLog.Android

[![Latest release](https://img.shields.io/github/release/wheellog/wheellog.android.svg)](https://github.com/wheellog/wheellog.android/releases/latest)
[![Build Status](https://github.com/wheellog/wheellog.android/workflows/Gradle%20CI/badge.svg?branch=master)](https://github.com/wheellog/wheellog.android/actions)

Unofficial App for EUC on Android. 
The following manufacturers are supported:
- InMotion
- KingSong
- Gotway
- Veteran
- Ninebot Zx, S2, C/P/E+, Mini

| <img src='https://github.com/Wheellog/Wheellog.Android/assets/27482193/2dd0d43b-53a6-4dc4-9794-ddee620628bb' width=400 /> | <img src='https://user-images.githubusercontent.com/27482193/114169041-bdb6ee00-9939-11eb-8fb9-f07b9eac1b2f.png' width=400 /> | <img src='https://github.com/Wheellog/Wheellog.Android/assets/27482193/d7fb7006-1ab3-4ca1-be9f-8d59ec88e376' width=400 />
|--|--|--|

## Contributing

### 🛠️ Pull requests
A pull request is a way to suggest changes in this repository. We accept pull requests in the `master` branch.

## App stores

 [![google-play-badge](http://github.com/Wheellog/Wheellog.Android/assets/27482193/8cc988b8-d5af-4c3f-a87a-9cd2c72f4b65)](https://play.google.com/store/apps/details?id=com.cooper.wheellog)
 [![huawei-app-gallery](http://github.com/Wheellog/Wheellog.Android/assets/27482193/bbf2dbea-95cf-465e-9244-ba12e9aa2fe0)](https://appgallery.huawei.com/#/app/C109077151)

## ⌚ Smart watch applications

- [Samsung Gear](https://github.com/juliomap/WheelLog-Tizen)
- Smart watch from Pebble company. Discontinued in 2016. [app code](https://github.com/JumpMaster/WheelLogPebble)
- Garmin Connect IQ [watch application](https://github.com/Wheellog/Companion.Garmin).
You can also download the app from [ConnectIQ Store](https://apps.garmin.com/en-US/apps/35719a02-8a5d-46bc-b474-f26c54c4e045).
- Xiaomi [Mi band 3/4/5/6](https://github.com/Wheellog/Wheellog.Android/wiki/Work-with-Mi-Band)

## 🚀 Quick start

Minimum requirements:
- Device running Windows, macOS, Linux or chromeOS
- Android device [in dev mode and enable USB debugging](https://developer.android.com/studio/debug/dev-options)

Let's start
- Download, install and open [Android studio](https://developer.android.com/studio/)
- In main menu `File -> New -> Project From Version Control` 

![image](https://user-images.githubusercontent.com/27482193/115096600-8abebc80-9f2e-11eb-9ba5-3a70dba14e17.png)
- Insert URL `https://github.com/Wheellog/Wheellog.Android.git` and click on `Clone`
- Install SDK and connect android device
- Setup private tokens in `local.properties`
  - [AppMetrica](https://appmetrica.yandex.ru/) `metrica_api`
  - [ElectroClub](https://electro.club/) `ec_accessToken` _(optional)_
- Click `Run` button - Enjoy!

## 💤 Based on

[palachzzz fork](https://github.com/palachzzz/WheelLogAndroid)
