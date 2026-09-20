#ifndef R2_CATALOG_ADMISSION
#define R2_CATALOG_ADMISSION
bool r2OpaqueContactDefinition(uint material) {
    switch(material) { case 0u: case 1u: case 2u: case 3u: case 4u: case 5u: case 6u: case 7u: case 10u: case 11u: case 12u: case 13u: case 14u: case 15u: case 16u: case 17u: case 20u: case 21u: case 22u: case 23u: case 26u: case 27u: case 28u: case 29u: case 30u: case 31u: case 32u: case 33u: case 34u: case 35u: case 36u: case 37u: case 38u: case 39u: case 40u: case 41u: case 42u: case 45u: case 46u: case 47u: return true; default: return false; }
}
bool r2GlassSheetDefinition(uint material) { return material==48u || material==49u; }
#endif
