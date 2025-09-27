**feat: add Pineapple-P (g3g3) support for GPU table parsing and generation**

* Implemented special handling for `pineapplep_singleBin`
* Updated `decode()` to recognize `qcom,gpu-pwrlevel-bins` structure
* Adjusted `genTable()` to output nested `gpu-pwrlevel-bins` / `gpu-pwrlevels-0` nodes
* Extended `offset_initial_level()` and `patch_throttle_level()` to modify Pineapple-P headers correctly
* Kept backward compatibility with all existing chip variants

This enables GPU table editing to work properly on Pineapple-P devices (g3g3).
