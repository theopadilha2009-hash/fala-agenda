package com.theopadilha.falaagenda.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MigrationTest {
    @Test
    fun migration1to2TemVersaoCerta() {
        assertThat(AppDatabase.MIGRATION_1_2.startVersion).isEqualTo(1)
        assertThat(AppDatabase.MIGRATION_1_2.endVersion).isEqualTo(2)
        assertThat(AppDatabase.MIGRATE_1_2_SQL).isEqualTo(
            "ALTER TABLE task_series ADD COLUMN amountCents INTEGER",
        )
    }
}
