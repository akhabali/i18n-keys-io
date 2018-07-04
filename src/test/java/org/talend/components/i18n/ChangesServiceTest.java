package org.talend.components.i18n;

import org.junit.jupiter.api.Test;

class ChangesServiceTest {

    @Test
    void changes() {
        final ChangesService changesService = new ChangesService("C:\\Users\\akhabali\\dev\\github\\components-ee",
                "C:\\Users\\akhabali\\Desktop\\to_be_translated",
                new Configuration());
        changesService.open();
        changesService.exportChanged("17/04/2016");
        changesService.close();
    }
}
