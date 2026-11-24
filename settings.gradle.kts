rootProject.name = "kpostgres"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// Модули репозитория.
//
// `kpostgres` — сам драйвер, без ORM и без пула: он обязан работать
// standalone, чтобы его можно было взять в проект, где своя обвязка.
//
// `kpostgres-pool` — адаптер к `kdatabase`: Connection, PreparedStatement,
// ResultSet, перевод `:name` → `$N`. Зависит от контрактов, не от ORM.
//
// `kpostgres-orm` — адаптер к `korm`. Отдельным модулем, чтобы ядро драйвера
// не тянуло ORM: потребителю, которому нужен только драйвер, она не нужна.
include(":kpostgres-pool")
include(":kpostgres-orm")
