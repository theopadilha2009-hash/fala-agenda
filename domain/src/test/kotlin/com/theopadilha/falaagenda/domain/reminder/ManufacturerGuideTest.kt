package com.theopadilha.falaagenda.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O guia de "não matar alarmes" por fabricante.
 *
 * O aparelho diz o fabricante de formas que não batem entre si ("Xiaomi", "xiaomi",
 * "Redmi", "HUAWEI TECHNOLOGIES CO., LTD."): quem não é reconhecido volta para o texto
 * genérico, e nunca fica sem instrução nenhuma.
 */
class ManufacturerGuideTest {
    private fun fabricante(raw: String?) = ManufacturerGuide.forManufacturer(raw).manufacturer

    @Test
    fun xiaomiChegaEscritoDeVariasFormas() {
        // O `Build.MANUFACTURER` de um Redmi é "Xiaomi", mas o de um POCO já saiu como
        // "Xiaomi"/"Redmi"/"POCO" conforme a MIUI — todos são o mesmo aparelho para ela.
        assertThat(listOf("Xiaomi", "xiaomi", "XIAOMI", " Redmi ", "Redmi", "POCO", "Poco X3").map { fabricante(it) })
            .containsExactly(
                Manufacturer.XIAOMI,
                Manufacturer.XIAOMI,
                Manufacturer.XIAOMI,
                Manufacturer.XIAOMI,
                Manufacturer.XIAOMI,
                Manufacturer.XIAOMI,
                Manufacturer.XIAOMI,
            )
    }

    @Test
    fun fabricantesConhecidosSaoReconhecidosEmQualquerCaixa() {
        val casos = mapOf(
            "samsung" to Manufacturer.SAMSUNG,
            "SAMSUNG" to Manufacturer.SAMSUNG,
            "Samsung Electronics" to Manufacturer.SAMSUNG,
            "motorola" to Manufacturer.MOTOROLA,
            "Motorola Mobility LLC." to Manufacturer.MOTOROLA,
            "HUAWEI" to Manufacturer.HUAWEI,
            "HUAWEI TECHNOLOGIES CO., LTD." to Manufacturer.HUAWEI,
            "HONOR" to Manufacturer.HONOR,
            "OnePlus" to Manufacturer.ONEPLUS,
            "ONEPLUS" to Manufacturer.ONEPLUS,
            "ASUS" to Manufacturer.ASUS,
            "Nokia" to Manufacturer.NOKIA,
            "vivo" to Manufacturer.VIVO,
            "VIVO" to Manufacturer.VIVO,
            "OPPO" to Manufacturer.OPPO,
            "oppo" to Manufacturer.OPPO,
            "realme" to Manufacturer.REALME,
            "Realme" to Manufacturer.REALME,
            "TCL" to Manufacturer.TCL,
            "Sony" to Manufacturer.SONY,
            "Sony Ericsson" to Manufacturer.SONY,
            "LG" to Manufacturer.LG,
            "LGE" to Manufacturer.LG,
            "LG Electronics" to Manufacturer.LG,
        )

        casos.forEach { (raw, esperado) ->
            assertThat(fabricante(raw)).isEqualTo(esperado)
        }
    }

    @Test
    fun fabricanteDesconhecidoOuAusenteVoltaParaOTextoGenerico() {
        // AOSP responde string vazia; aparelho de marca que a lista não cobre responde o
        // nome dela. Nenhum dos dois pode ficar sem instrução — os dois são o texto de hoje.
        listOf(null, "", "   ", "Zebra", "Positivo", "alcatel one touch").forEach { raw ->
            val guia = ManufacturerGuide.forManufacturer(raw)
            assertThat(guia.manufacturer).isNull()
            assertThat(guia.steps).isEqualTo(ManufacturerGuide.GENERIC.steps)
            assertThat(guia.shortcut).isEqualTo(VendorSettings.NONE)
        }
    }

    @Test
    fun todaMarcaReconhecidaTemGuiaEnxutoComONomeNaTela() {
        Manufacturer.entries.forEach { fabricante ->
            val guia = ManufacturerGuide.forManufacturer(fabricante.name)

            assertThat(guia.title).contains(fabricante.displayName)
            // Ela lê a tela de uma vez; passo demais é o mesmo que passo nenhum.
            assertThat(guia.steps.size).isAtLeast(1)
            assertThat(guia.steps.size).isAtMost(5)
            guia.steps.forEach { passo -> assertThat(passo).isNotEmpty() }
            // Sem o nome do aplicativo no passo, ela não sabe qual item procurar na lista.
            assertThat(guia.steps.count { it.contains("Fala Agenda") }).isAtLeast(1)
        }
    }

    @Test
    fun asMarcasQueODontkillmyappCobreNaoRecebemOTextoGenerico() {
        // LG e TCL não têm página no dontkillmyapp: ficam de fora de propósito, com o
        // caminho padrão e o nome da marca no título.
        val comConteudoProprio = Manufacturer.entries - setOf(Manufacturer.LG, Manufacturer.TCL)

        comConteudoProprio.forEach { fabricante ->
            assertThat(ManufacturerGuide.forManufacturer(fabricante.name).steps)
                .isNotEqualTo(ManufacturerGuide.GENERIC.steps)
        }
    }

    @Test
    fun todoGuiaComPassosDoUpstreamDizDeOndeVem() {
        // CC-BY-4.0 do dontkillmyapp: sem o crédito a licença não é respeitada — e o
        // crédito não pode aparecer sobre texto que não é de lá (o genérico é do app).
        Manufacturer.entries.forEach { marca ->
            val guia = ManufacturerGuide.forManufacturer(marca.name)
            val doUpstream = guia.steps != ManufacturerGuide.GENERIC.steps

            assertThat(guia.credit != null).isEqualTo(doUpstream)
        }
        assertThat(ManufacturerGuide.GENERIC.credit).isNull()
    }

    @Test
    fun soOCreditoDoDontkillmyappValeParaOsGuiasQueVemDeLa() {
        assertThat(ManufacturerGuide.forManufacturer("Xiaomi").credit)
            .isEqualTo("Instruções baseadas em dontkillmyapp.com")
        // Honor não tem página no upstream: os passos são adaptados da página da Huawei,
        // que é do dontkillmyapp, e o crédito segue valendo.
        assertThat(ManufacturerGuide.forManufacturer("Honor").credit)
            .isEqualTo("Instruções baseadas em dontkillmyapp.com")
        assertThat(ManufacturerGuide.forManufacturer("LG").credit).isNull()
        assertThat(ManufacturerGuide.forManufacturer("Zebra").credit).isNull()
    }

    @Test
    fun apelidoValeNoComecoNoFimENoMeio() {
        assertThat(fabricante("Beijing Xiaomi")).isEqualTo(Manufacturer.XIAOMI)
        assertThat(fabricante("BBK vivo")).isEqualTo(Manufacturer.VIVO)
        // Dentro de outra palavra não é apelido: "vivobook" não é um Vivo.
        assertThat(fabricante("vivobook")).isNull()
    }

    @Test
    fun aparelhoDaPropriaHMDSemSerNokiaNaoEntraNoGuiaNokia() {
        // "HMD Global" era o que os Nokia antigos respondiam, e desde 2024 é o que respondem
        // os aparelhos da própria HMD, que não têm a tela que o guia do Nokia descreve.
        assertThat(fabricante("HMD Global")).isNull()
        assertThat(fabricante("Nokia")).isEqualTo(Manufacturer.NOKIA)
    }

    @Test
    fun oGuiaDoNokiaCarregaARessalvaDoPowerSaver() {
        // A HMD desligou o Power saver nos aparelhos com Pie ou mais novo (8/2019): mandar
        // ela procurar um aplicativo que não existe mais é caminho sem saída.
        val passos = ManufacturerGuide.forManufacturer("Nokia").steps
        val ressalva = passos.filter { it.contains("Power saver") }

        assertThat(ressalva).hasSize(1)
        assertThat(ressalva.first()).contains("2019")
    }

    @Test
    fun oRotuloDoAtalhoSoExisteQuandoHaTelaParaAbrir() {
        assertThat(ManufacturerGuide.forManufacturer("Xiaomi").shortcutBrand).isEqualTo("Xiaomi")
        // Marca reconhecida sem tela própria: quem atende é a tela de bateria do sistema.
        assertThat(ManufacturerGuide.forManufacturer("Samsung").shortcutBrand).isNull()
        assertThat(ManufacturerGuide.forManufacturer("LG").shortcutBrand).isNull()
        assertThat(ManufacturerGuide.GENERIC.shortcutBrand).isNull()
    }

    @Test
    fun marcaSemPaginaNoUpstreamNaoPrometeUmAjusteQueNaoExiste() {
        val lg = ManufacturerGuide.forManufacturer("LG")

        assertThat(lg.manufacturer).isEqualTo(Manufacturer.LG)
        assertThat(lg.title).contains("não há ajuste extra")
        assertThat(lg.steps).isEqualTo(ManufacturerGuide.GENERIC.steps)
    }

    @Test
    fun oPassoFinalSempreApontaParaOFalaAgenda() {
        // Sem o nome do aplicativo dentro do passo, ela não sabe qual item procurar na
        // lista de aplicativos do celular — que é onde quase todos esses caminhos passam.
        val guias = listOf(ManufacturerGuide.GENERIC) +
            Manufacturer.entries.map { ManufacturerGuide.forManufacturer(it.name) }

        guias.forEach { guia ->
            assertThat(guia.steps.count { it.contains("Fala Agenda") }).isAtLeast(1)
        }
    }

    @Test
    fun soOfereceAtalhoOndeExisteTelaConhecida() {
        // O atalho abre uma tela do fabricante; oferecer um atalho que não abre é pior do
        // que não oferecer nada.
        assertThat(ManufacturerGuide.forManufacturer("Xiaomi").shortcut)
            .isEqualTo(VendorSettings.XIAOMI_AUTOSTART)
        assertThat(ManufacturerGuide.forManufacturer("HUAWEI").shortcut)
            .isEqualTo(VendorSettings.HUAWEI_STARTUP)
        assertThat(ManufacturerGuide.forManufacturer("Zebra").shortcut)
            .isEqualTo(VendorSettings.NONE)
    }

    @Test
    fun oTituloDizDeQualAparelhoE() {
        assertThat(ManufacturerGuide.forManufacturer("Xiaomi").title).contains("Xiaomi")
        assertThat(ManufacturerGuide.forManufacturer(null).title).contains("celular")
    }
}
