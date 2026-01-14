# BetterMOTD

![Paper](https://img.shields.io/badge/Paper-1.21.x-ffffff?style=flat-square&logo=papermc&logoColor=blue)
![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A?style=flat-square&logo=gradle&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square&logo=opensourceinitiative&logoColor=white)

**BetterMOTD** - лёгкий и гибкий плагин для **Paper** серверов Minecraft, позволяющий настраивать
динамический **MOTD** и **иконку сервера** с поддержкой анимаций, HEX-цветов и градиентов.

Плагин не использует NMS, не нагружает сервер и легко кастомизируется через `config.yml`.

---

## ✨ Features

- 🎨 Динамический MOTD (MiniMessage)
- 🌈 HEX-цвета, градиенты и форматирование
- 🔁 Анимированный MOTD (кадры)
- 🖼️ Смена и анимация иконки сервера
- 🎯 Взвешенный рандом или sticky-режим по IP
- 📁 Автосоздание папки `icons/`
- 🧩 Заглушка `default.png` при первом запуске
- ⚡ Минимальный overhead, без NMS

---

## 📦 Compatibility

- **Minecraft:** 1.21 – 1.21.11  
- **Server:** Paper  
- **Java:** 21+

---

## 🚀 Installation

1. Перейдите на страницу [релизов](https://github.com/AREKKUZZERA/better-motd/releases)
2. Скачайте **последний релиз** (`BetterMOTD-<version>.jar`)
3. Поместите JAR-файл в папку `plugins/`
4. Запустите сервер
5. Настройте файл `plugins/BetterMOTD/config.yml`
6. Перезапустите сервер или выполните `/reload` (не рекомендуется, могут быть ошибки)

---

## 🖼️ Server Icons

- Формат: **PNG**
- Размер: **64×64**
- Путь: `plugins/BetterMOTD/icons/`

Если папка пуста, плагин автоматически создаст:

```txt
icons/default.png
````

---

## 📝 Example MOTD (MiniMessage)

```yml
motd:
  - "<gradient:#00ffcc:#0099ff>Better Server</gradient>"
  - "<gray>Online players: <green>%online%</green></gray>"
```

---

## ⚙️ Configuration

Основные параметры:

* режим выбора (`RANDOM` / `STICKY_PER_IP`)
* вес пресетов
* анимация MOTD
* анимация иконок

Все настройки находятся в `config.yml`.

---

## 📄 License

This project is licensed under the **MIT License**.
You are free to use, modify, and distribute it.
