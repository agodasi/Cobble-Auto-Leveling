/*
 * Copyright (C) 2023 Cobblemon Contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

{
    use(battle, pokemon, itemId, data) {
        var statuses = [];
        var shouldCure = true;
        if (data.length > 0) {
            shouldCure = false;
            for (const status of data) {
                if (pokemon.status == status) {
                    shouldCure = true;
                }
            }
        }

        if (shouldCure) {
            pokemon.cureStatus(true);
        }
        if (data.length == 0) {
            pokemon.removeVolatile('confusion');
        }
    }
}