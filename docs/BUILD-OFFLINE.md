# Сборка плагина без интернета (Jenkins + Nexus)

Как собрать плагин во внутреннем контуре: интернета нет, всё берётся из Nexus, сертификаты свои, IDE — IntelliJ IDEA Community с GitHub.
Файлы для CI лежат в `tools/ci/`:

| Файл | Что делает |
|---|---|
| `nexus.init.gradle` | init-скрипт Gradle: перенаправляет все репозитории сборки на прокси Nexus, проект не меняется |
| `truststore.sh` | truststore Java: сертификаты JDK плюс корневые сертификаты компании |
| `Jenkinsfile` | пример конвейера: checkout с сабмодулем, инструменты из Nexus, тесты, `buildPlugin` |

Результат сборки — `build/distributions/idea-golang-support-<версия>.zip`. Он ставится в любую IDE на платформе 2026.1+ через Settings | Plugins | Install Plugin from Disk.

## 1. Что сборка берёт из сети

| Что | Откуда (оригинал) | Зачем |
|---|---|---|
| Gradle 9.7.1 | `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip` | wrapper (`gradlew`) |
| Плагины Gradle: `org.jetbrains.kotlin.jvm` 2.3.21, `org.jetbrains.intellij.platform` (+ `.module`, `.grammarkit`) 2.19.0 | `https://plugins.gradle.org/m2` | сборка |
| Библиотеки: Kotlin, junit 4.13.2, opentest4j 1.3.0, зависимости плагинов Gradle | Maven Central `https://repo.maven.apache.org/maven2` | компиляция, тесты |
| `com.jetbrains.intellij.platform:test-framework` и др. артефакты платформы с номером сборки IDE | `https://www.jetbrains.com/intellij-repository/releases` | тесты |
| Grammar-Kit, JFlex (генерация парсера и лексера Go), `java-compiler-ant-tasks` (инструментирование), coroutines-агент | `https://cache-redirector.jetbrains.com/intellij-dependencies` | генерация кода, сборка |
| IntelliJ IDEA Community 2026.1.x | GitHub (релизы `JetBrains/intellij-community`) | платформа, против которой собирается плагин |
| JDK 21 | любой дистрибутив (Temurin, Liberica, JBR) | toolchain модулей go-psi (`jvmToolchain(21)`) и запуск Gradle |
| delve v1.27.2 (git-сабмодуль `third_party/delve`) | `https://github.com/go-delve/delve.git` | исходники delve кладутся в ZIP плагина |

Не нужны для сборки: JetBrains Runtime и установщики IDE (IDE берётся локально), Marketplace (только для `runIdeForUiTests`), Plugin Verifier (`verifyPlugin`),
корпуса и бенчмарки (`corpusTest`, `benchmark` — им нужен установленный Go). Init-скрипт убирает Ivy-репозитории установщиков и JBR из сборки сам.

## 2. Что завести в Nexus

**Maven proxy-репозитории** (имена по умолчанию, другие — через переменные `NEXUS_REPO_<KEY>`, см. ниже):

| Имя в Nexus | Remote storage | Ключ |
|---|---|---|
| `maven-central` | `https://repo.maven.apache.org/maven2` | `MAVEN_CENTRAL` |
| `gradle-plugins` | `https://plugins.gradle.org/m2` | `GRADLE_PLUGINS` |
| `intellij-releases` | `https://www.jetbrains.com/intellij-repository/releases` | `INTELLIJ_RELEASES` |
| `intellij-dependencies` | `https://cache-redirector.jetbrains.com/intellij-dependencies` | `INTELLIJ_DEPENDENCIES` |
| `intellij-snapshots` (необязательно) | `https://www.jetbrains.com/intellij-repository/snapshots` | `INTELLIJ_SNAPSHOTS` |
| `jetbrains-marketplace` (необязательно) | `https://plugins.jetbrains.com/maven` | `JETBRAINS_MARKETPLACE` |

Version policy — Mixed (в `intellij-releases` и `intellij-dependencies` есть и релизы, и сборки с суффиксами), Layout policy — Permissive.
Если у Nexus тоже нет выхода наружу, вместо proxy — hosted-репозитории с теми же именами, заполненные с машины с интернетом (раздел 8).

**Raw-репозиторий** (например `raw-tools`) с файлами:
- `gradle/gradle-9.7.1-bin.zip` — с services.gradle.org;
- `idea/ideaIC-2026.1.4.tar.gz` — IntelliJ IDEA Community с GitHub;
- `jdk/OpenJDK21U-jdk_x64_linux.tar.gz` — JDK 21.

**Git-зеркало delve**: `https://github.com/go-delve/delve.git` во внутренний Git (Gitea, GitLab, Bitbucket) с тегом `v1.27.2`.
В репозитории delve уже есть `vendor/`, своих Go-зависимостей сборка плагина не тянет.

## 3. Сертификаты (truststore)

Java не берёт сертификаты системы: корневые сертификаты компании нужно добавить в truststore. Их читают три JVM: wrapper (скачивает Gradle),
демон Gradle (зависимости) и, на всякий случай, тестовые JVM.

```sh
tools/ci/truststore.sh "$JDK21" /etc/pki/ca-trust/source/anchors "$TOOLS/truststore.jks"   # cacerts JDK + все *.crt/*.pem каталога
```

Подключение:
- демон Gradle — `$GRADLE_USER_HOME/gradle.properties`:
  ```properties
  systemProp.javax.net.ssl.trustStore=/path/to/truststore.jks
  systemProp.javax.net.ssl.trustStorePassword=changeit
  ```
- wrapper (до старта Gradle) — переменная окружения `GRADLE_OPTS="-Djavax.net.ssl.trustStore=/path/to/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"`;
- git — `git config --global http.sslCAInfo /etc/pki/tls/certs/ca-bundle.crt` (бандл системы с корнями компании);
- curl — `--cacert` с тем же бандлом.

Проще, если образ агента свой: импортировать корни прямо в `$JAVA_HOME/lib/security/cacerts` JDK 21 тем же `keytool -importcert` — тогда
свойства не нужны. На агенте с Windows вместо файла можно взять хранилище системы: `-Djavax.net.ssl.trustStoreType=Windows-ROOT`.

Проверка: `"$JDK21/bin/keytool" -list -keystore truststore.jks -storepass changeit | grep corp-`.

## 4. Gradle из Nexus

Wrapper берёт дистрибутив из `gradle/wrapper/gradle-wrapper.properties`. В CI адрес подменяется перед сборкой (в репозитории его не меняем):

```sh
sed -i "s#^distributionUrl=.*#distributionUrl=https://nexus.example.local/repository/raw-tools/gradle/gradle-9.7.1-bin.zip#" gradle/wrapper/gradle-wrapper.properties
```

Если Nexus требует логин, wrapper берёт его из `$GRADLE_USER_HOME/gradle.properties`: `systemProp.gradle.wrapperUser=…`, `systemProp.gradle.wrapperPassword=…`.

## 5. Репозитории: init-скрипт

`tools/ci/nexus.init.gradle` заменяет адреса всех Maven-репозиториев сборки (плагины Gradle, Maven Central и репозитории платформы IntelliJ, которые
добавляет `intellijPlatform { defaultRepositories() }`) на `${NEXUS_URL}/repository/<имя>`. Проект при этом не меняется.

| Переменная | Значение |
|---|---|
| `NEXUS_URL` | `https://nexus.example.local` — без `/repository` |
| `NEXUS_USER`, `NEXUS_PASSWORD` | учётная запись Nexus на чтение, если нужна (в Jenkins — из credentials) |
| `NEXUS_REPO_<KEY>` | другое имя репозитория, например `NEXUS_REPO_MAVEN_CENTRAL=maven-proxy` |
| `NEXUS_REPORT_ONLY=true` | ничего не менять, только напечатать репозитории сборки и куда они уйдут — для настройки |

Неизвестный скрипту репозиторий валит сборку с его адресом: значит, нужен ещё один прокси и строка в `SOURCES` скрипта.

```sh
NEXUS_REPORT_ONLY=true ./gradlew --init-script tools/ci/nexus.init.gradle help --no-configuration-cache   # что и куда
```

## 6. IDE и JDK

- IntelliJ IDEA Community распаковать в каталог агента и передать сборке: `-PlocalIdePath=/path/to/ideaIC` (перекрывает путь из `gradle.properties`).
  Номер сборки IDE должен быть 261 и выше (`sinceBuild` плагина). Плагин Database есть только в Ultimate: сборка подключает его, только если
  он есть в IDE (`product-info.json`), а подсветка SQL в строках включается там, где он установлен.
- JDK 21 — `JAVA_HOME` для Gradle и toolchain модулей go-psi. Чтобы Gradle не искал и не скачивал JDK сам:
  ```properties
  org.gradle.java.installations.paths=/path/to/jdk21
  org.gradle.java.installations.auto-download=false
  org.gradle.java.installations.auto-detect=false
  ```
- Тестам нужен `test-framework` ровно той сборки платформы, что у IDE (например `261.12345.67`). Он берётся из `intellij-releases`. Если у сборки
  Community с GitHub номер, которого нет в репозитории JetBrains, тесты не разрешатся — тогда собирать без них: `buildPlugin -x test`
  (или с IDE того же номера, что опубликован в `intellij-releases`).

## 7. Команды

```sh
export JAVA_HOME=/path/to/jdk21
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/path/to/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
export NEXUS_URL=https://nexus.example.local NEXUS_USER=… NEXUS_PASSWORD=…
git config --global url."https://git.example.local/mirrors/".insteadOf https://github.com/go-delve/
git submodule update --init --depth 1

./gradlew --no-daemon --no-configuration-cache --init-script tools/ci/nexus.init.gradle \
    -PlocalIdePath=/path/to/ideaIC \
    :test :go-psi-core:test :go-psi-semantic:test :go-psi-ide:test buildPlugin --continue
```

- `--offline` в CI не нужен (он для машины разработчика, где Nexus нет): зависимости идут из Nexus. Каталог `GRADLE_USER_HOME` стоит держать
  между сборками — тогда всё скачивается один раз.
- `--no-configuration-cache`: init-скрипт меняет репозитории, а разовой сборке в CI кэш конфигурации ничего не даёт.
- Отчёты тестов — `**/build/test-results/test/*.xml`, артефакт — `build/distributions/*.zip`.
- Пример целиком — `tools/ci/Jenkinsfile`: Linux-агент, credentials `nexus-read`, кэш Gradle в `/var/cache/jenkins/gradle-golang-support`.

## 8. Если у Nexus нет выхода наружу

Proxy-репозитории тогда не скачают ничего, и их нужно заполнить заранее с машины с интернетом:

1. Собрать там плагин обычным образом с чистым `GRADLE_USER_HOME` той же командой (без init-скрипта): Gradle скачает ровно то, что нужно.
2. Выгрузить `GRADLE_USER_HOME/caches/modules-2/files-2.1` в hosted-репозитории Nexus в раскладке Maven (`группа/артефакт/версия/файл`):
   структура кэша отличается от Maven (лишний каталог с хешем), её разворачивают скриптом или утилитой загрузки в Nexus
   (например `mvn deploy:deploy-file` на каждый артефакт вместе с `.pom`).
3. Повторять при смене версий: IDE, Kotlin, IntelliJ Platform Gradle Plugin (`gradle/libs.versions.toml`, `build.gradle.kts`).

## 9. Частые ошибки

| Симптом | Причина |
|---|---|
| `PKIX path building failed` | корни компании не в truststore той JVM, что ходит в сеть: wrapper — `GRADLE_OPTS`, демон — `systemProp.*` |
| `nexus.init.gradle: no Nexus proxy for these repositories` | сборка обратилась к новому адресу: прокси в Nexus и строка в `SOURCES` |
| `Could not resolve com.jetbrains.intellij.platform:test-framework:<номер>` | номера сборки IDE нет в `intellij-releases` (раздел 6) |
| `third_party/delve is empty: run git submodule update --init` | сабмодуль не скачан: зеркало и `insteadOf` |
| `Cannot find a Java installation … languageVersion=21` | нет JDK 21 в `org.gradle.java.installations.paths` |
| wrapper: `401` при скачивании Gradle | нет `systemProp.gradle.wrapperUser` / `wrapperPassword` |
