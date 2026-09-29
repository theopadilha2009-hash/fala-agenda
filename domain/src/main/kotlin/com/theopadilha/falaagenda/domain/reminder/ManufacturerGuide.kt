package com.theopadilha.falaagenda.domain.reminder

import java.util.Locale

/**
 * O que cada fabricante faz para não deixar o aviso tocar, e como desligar isso.
 *
 * O `Build.MANUFACTURER` é texto livre e não bate consigo mesmo ("Xiaomi", "xiaomi",
 * "Redmi", "HUAWEI TECHNOLOGIES CO., LTD."): [of] normaliza antes de comparar, e o que
 * não é reconhecido cai no guia genérico — nunca fica sem instrução.
 *
 * Os passos vêm do dontkillmyapp.com (urbandroid-team/dont-kill-my-app, CC-BY-4.0),
 * traduzidos e encurtados para uma tela só. Ver `third_party/dontkillmyapp/NOTICE.md`.
 */
enum class Manufacturer(val displayName: String, private val aliases: List<String>) {
    XIAOMI("Xiaomi", listOf("xiaomi", "redmi", "poco")),
    SAMSUNG("Samsung", listOf("samsung")),
    MOTOROLA("Motorola", listOf("motorola")),
    HUAWEI("Huawei", listOf("huawei")),
    HONOR("Honor", listOf("honor")),
    ONEPLUS("OnePlus", listOf("oneplus")),
    ASUS("Asus", listOf("asus", "asustek")),
    NOKIA("Nokia", listOf("nokia", "hmd")),
    VIVO("Vivo", listOf("vivo")),
    OPPO("Oppo", listOf("oppo")),
    REALME("Realme", listOf("realme")),
    SONY("Sony", listOf("sony")),
    TCL("TCL", listOf("tcl")),
    LG("LG", listOf("lg", "lge")),
    ;

    companion object {
        fun of(raw: String?): Manufacturer? {
            val nome = normalize(raw)
            if (nome.isEmpty()) return null
            return entries.firstOrNull { marca -> marca.aliases.any { bate(nome, it) } }
        }

        /** Minúsculas, sem pontuação, sem espaço sobrando: "  HUAWEI CO., LTD. " → "huawei co ltd". */
        internal fun normalize(raw: String?): String =
            raw.orEmpty()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()

        /**
         * O nome pode vir inteiro ("motorola mobility llc") ou já curto ("redmi"): o apelido
         * vale no começo ou como palavra solta, nunca no meio de outra palavra — "vivo" não
         * pode casar com "vivobook".
         */
        private fun bate(nome: String, apelido: String): Boolean =
            nome == apelido || nome.startsWith("$apelido ") || nome.contains(" $apelido ")
    }
}

/** Tela de ajustes do fabricante que dá para abrir por Intent. */
enum class VendorSettings { NONE, XIAOMI_AUTOSTART, HUAWEI_STARTUP, ONEPLUS_STARTUP, ASUS_AUTOSTART, VIVO_STARTUP, OPPO_STARTUP }

/**
 * O que mostrar para ela quando o aviso não toca.
 *
 * [manufacturer] nulo é aparelho que a lista não reconhece (ou AOSP, que responde vazio):
 * vale o texto genérico de sempre.
 */
class ManufacturerGuide private constructor(
    val manufacturer: Manufacturer?,
    val title: String,
    val steps: List<String>,
    val shortcut: VendorSettings,
) {
    /** Exigência do CC-BY-4.0 do dontkillmyapp: o crédito acompanha o conteúdo. */
    val credit: String = CREDIT

    companion object {
        const val CREDIT = "Instruções baseadas em dontkillmyapp.com"

        val GENERIC = ManufacturerGuide(
            manufacturer = null,
            title = "No seu celular, deixe o Fala Agenda assim:",
            steps = listOf(
                "Toque em Ajustes e depois em Aplicativos.",
                "Toque em Fala Agenda e depois em Bateria (ou Economia de bateria).",
                "Escolha Sem restrições.",
                "Se o aviso continuar falhando, procure nos Ajustes por início automático " +
                    "(ou autostart) e ligue o Fala Agenda lá.",
            ),
            shortcut = VendorSettings.NONE,
        )

        fun forManufacturer(raw: String?): ManufacturerGuide {
            val marca = Manufacturer.of(raw) ?: return GENERIC
            return ManufacturerGuide(
                manufacturer = marca,
                title = "No seu ${marca.displayName}, deixe o Fala Agenda assim:",
                // Marca conhecida que o dontkillmyapp não cobre (LG, TCL) fica com o caminho
                // padrão — o nome dela no título já diz que o guia olhou para este aparelho.
                steps = stepsOf(marca) ?: GENERIC.steps,
                shortcut = shortcutOf(marca),
            )
        }

        private fun shortcutOf(marca: Manufacturer): VendorSettings = when (marca) {
            Manufacturer.XIAOMI -> VendorSettings.XIAOMI_AUTOSTART
            Manufacturer.HUAWEI -> VendorSettings.HUAWEI_STARTUP
            Manufacturer.ONEPLUS -> VendorSettings.ONEPLUS_STARTUP
            Manufacturer.ASUS -> VendorSettings.ASUS_AUTOSTART
            Manufacturer.VIVO -> VendorSettings.VIVO_STARTUP
            Manufacturer.OPPO, Manufacturer.REALME -> VendorSettings.OPPO_STARTUP
            else -> VendorSettings.NONE
        }

        private fun stepsOf(marca: Manufacturer): List<String>? = when (marca) {
            Manufacturer.XIAOMI -> listOf(
                "Toque em Ajustes e depois em Aplicativos.",
                "Toque em Fala Agenda, entre em Economia de bateria e escolha Sem restrições.",
                "Volte e toque em Início automático: ligue o Fala Agenda.",
                "Abra as telas recentes e puxe o Fala Agenda para baixo, até aparecer o cadeado.",
            )

            Manufacturer.SAMSUNG -> listOf(
                "Toque em Ajustes e depois em Bateria e cuidado do dispositivo.",
                "Toque em Bateria e depois em Limites de uso em segundo plano.",
                "Escolha Fala Agenda e marque Sem restrições.",
                "Toque em Aplicativos, em Fala Agenda, em Bateria e escolha Sem restrições.",
            )

            Manufacturer.MOTOROLA -> listOf(
                "Toque em Ajustes e depois em Aplicativos, em Fala Agenda.",
                "Toque em Uso de bateria e permita o uso em segundo plano.",
                "Toque no texto do meio da tela (não no botão ao lado) e escolha Sem restrições.",
                "Volte em Ajustes, entre em Bateria e desligue a Bateria adaptativa.",
            )

            Manufacturer.HUAWEI -> listOf(
                "Toque em Ajustes e depois em Bateria.",
                "Toque em Inicialização de aplicativos e escolha Gerenciar manualmente.",
                "Marque as três opções do Fala Agenda (iniciar sozinho, indireto e em segundo plano).",
                "Volte em Aplicativos, em Fala Agenda, em Consumo de energia e ligue Executar em segundo plano.",
            )

            Manufacturer.HONOR -> listOf(
                "Toque em Ajustes e depois em Aplicativos, em Fala Agenda.",
                "Toque em Consumo de energia e escolha Gerenciar manualmente.",
                "Ligue Executar em segundo plano e Iniciar automaticamente.",
                "Confira em Ajustes, em Bateria, que o Fala Agenda não está suspenso.",
            )

            Manufacturer.ONEPLUS -> listOf(
                "Toque em Ajustes e depois em Bateria.",
                "Toque em Otimização de bateria, em Todos os aplicativos, em Fala Agenda e escolha Não otimizar.",
                "Toque nos três pontinhos e em Otimização avançada: desligue a Otimização profunda.",
                "Volte e ligue o Fala Agenda em Início automático.",
            )

            Manufacturer.ASUS -> listOf(
                "Abra o aplicativo Mobile Manager (Gerenciador).",
                "Toque em PowerMaster e depois em Configurações.",
                "Desmarque Limpar em suspensão e Negar início automático de aplicativos.",
                "Em Ajustes, em Aplicativos, em Fala Agenda, em Bateria, escolha Sem restrições.",
            )

            Manufacturer.NOKIA -> listOf(
                "Toque em Ajustes e depois em Aplicativos, em Ver todos os aplicativos.",
                "Toque no menu do canto de cima e ligue Mostrar sistema.",
                "Procure Power saver (Economia de energia), abra e toque em Forçar parada.",
                "Confira em Aplicativos, em Fala Agenda, em Bateria que está Sem restrições.",
            )

            Manufacturer.VIVO -> listOf(
                "Toque em Ajustes e depois em Mais ajustes, em Aplicativos.",
                "Toque em Inicialização automática e ligue o Fala Agenda.",
                "Volte em Ajustes, entre em Bateria, em Consumo de energia em segundo plano.",
                "Encontre o Fala Agenda e permita o consumo alto de energia.",
            )

            Manufacturer.OPPO -> listOf(
                "Toque em Ajustes e depois em Aplicativos, em Fala Agenda.",
                "Toque em Uso de bateria e permita a execução em segundo plano.",
                "Volte e ligue Permitir inicialização automática.",
                "Segure o Fala Agenda nas telas recentes e toque no cadeado para travar.",
            )

            Manufacturer.REALME -> listOf(
                "Toque em Ajustes, em Aplicativos e em Inicialização automática: permita o Fala Agenda.",
                "Toque em Ajustes e depois em Bateria.",
                "Entre em Configurações de economia de energia e em Gerenciamento de bateria dos aplicativos.",
                "Escolha o Fala Agenda, permita a atividade em segundo plano e escolha Não otimizar.",
            )

            Manufacturer.SONY -> listOf(
                "Toque em Ajustes e depois em Bateria.",
                "Desligue o modo STAMINA (economia de bateria).",
                "Toque em Aplicativos, em Fala Agenda, em Bateria e escolha Sem restrições.",
            )

            // O dontkillmyapp não tem página destas duas: vale o caminho padrão.
            Manufacturer.TCL, Manufacturer.LG -> null
        }
    }
}
