package space.opensensor.family.air

import space.opensensor.units.formatFixed

/**
 * A particle value (µg/m³) as both apps print it. Whole numbers from 100 up, otherwise one decimal
 * with a trailing `.0` dropped, so 172.4 reads "172", 9.0 reads "9" and 12.34 reads "12.3".
 */
fun formatPm(value: Double): String =
    if (value >= 100) formatFixed(value, 0) else formatFixed(value, 1).removeSuffix(".0")
