# OptiForge

> OptiFine on Forge, with Forge Mod Loader loaded alongside.

版本号遵循 SemVer（当前 `0.1.0`，见 `gradle.properties` 的 `mod_version_base`）。

鍦?**Forge** 涓婂姞杞?**OptiFine** 鐨勬ā缁?鈥斺€?鍜?OptiFabric 鍦?Fabric 涓娿€丱ptifiNeoforge 鍦?NeoForge 涓婂仛鐨勪簨涓€鏍凤紝
鍙槸鐩爣鎹㈡垚 Forge銆?
> **鐘舵€侊細楠ㄦ灦锛屽皻鏈彲鐢ㄣ€?* 鐜板湪杩欎釜 jar 鍙仛涓€浠朵簨锛氬湪鐪熷疄 Forge 鍚姩鏃舵妸 OptiFine jar 鐨勫簳缁嗗拰
> OptiFine 瀹樻柟鐨?Forge 鍏煎澹版槑鍐欐垚涓€浠芥姤鍛婏紙`optiforge/diagnose.txt`锛夈€俙transformers()` 鏄┖鐨勶紝
> 涔熷氨鏄瀹冭繕娌℃湁淇换浣曚笢瑗裤€傚摢浜涘湴鏂硅淇紝鐢辫繖浠芥姤鍛婂湪鐪熸満涓婂畾 鈥斺€?瑙?[docs/STATE.md](docs/STATE.md)銆?
## 涓轰粈涔堥渶瑕佸畠

OptiFine 鐨勪笅杞介〉姣忎竴琛岄兘鏈変竴鍒?`Forge`锛?
- `Forge 47.2.18` 鈥斺€?璇ユ瀯寤哄０鏄庡吋瀹硅繖涓?Forge 鏋勫缓锛?- `Forge N/A` 鈥斺€?OptiFine 鏄庤**涓嶅吋瀹?Forge**锛坈hangelog 閲屽啓浣?`not compatible with Forge`锛夈€?
`Forge N/A` 鐨勬瀯寤哄湪 Forge 涓婄洿鎺ユ斁 `mods/` 鏄捣涓嶆潵鐨勶紝鑰岃繖浜涙瀯寤哄垎甯冨湪涓嶅皯鐗堟湰绾夸笂锛歚1.20.2`銆乣1.21`銆?`1.21.6`銆乣1.21.7`銆乣1.21.9`銆乣26.1.2`銆乣26.2` 鏁存潯绾块兘鏄?N/A銆傝〃閲岃繕鏈変竴鎵?鏃╂湡 preview 涓嶅吋瀹广€佸悗闈㈡煇涓€鐗堟墠寮€濮嬪吋瀹?
鐨勫崐鍏煎绾匡紙`1.21.3` 浠?`J2_pre8`銆乣1.21.8` 浠?`J6_pre13`銆乣1.21.10` 浠?`J7_pre6`銆乣1.21.11` 浠?`J8_pre3` 鈥︼級銆?
OptiForge 瑕佸仛鐨勫氨鏄妸杩欎簺鏋勫缓鎺ュ洖 Forge銆?
## 鐜板湪鑳藉仛浠€涔堬細璇婃柇

鎶?OptiForge 鍜?*浣犺嚜宸变笅杞界殑** OptiFine jar 涓€璧锋斁杩?Forge 瀹炰緥鐨?`mods/`锛屽惎鍔ㄤ竴娆★紝鐒跺悗鐪?`optiforge/diagnose.txt`銆傚畠浼氬啓鍑猴細

- 杩欐鍚姩鐪熷疄鐨?Minecraft / Forge / FML 鐗堟湰锛堜粠 `sun.java.command` 鐨?`--fml.*` 鍙傛暟璇伙紝涓嶄緷璧栦换浣?Forge 绫伙級锛?- `mods/` 閲屾瘡涓?OptiFine jar 鐨勫ぇ灏忋€丼HA-256銆乣buildof.txt` 鏋勫缓鏃堕棿銆乯ar 褰㈡€侊紙瀹夎鍣ㄥ舰鎬佽繕鏄鍒殑宸ュ叿鏀瑰啓杩囷級锛?- 琛ヤ竵璐熻浇娓呭崟锛歚patch/srg` / `patch/notch` / `patch/assets` 鍚勫灏戞潯锛屽叾涓?*鏈夋病鏈?* `net/minecraftforge` 鐩爣锛?- OptiFine 鑷繁甯︾殑绫伙細`srg/`銆乣notch/` 鍚勫灏戯紝鍏朵腑 `net/minecraftforge` 鏈夊灏戜釜锛?- 瀵圭収 `data/optifine-forge-matrix.csv`锛圤ptiFine 瀹樻柟 Forge 鍒楃殑瀹屾暣蹇収锛夊緱鍑鸿鏋勫缓瀵硅繖娆?Forge 鐗堟湰鐨勫垽瀹氾紱
- 涓嬩竴姝ヨ鏀瑰摢閲岀殑鍊欓€夋竻鍗曘€?
涔熷彲浠ュ畬鍏ㄧ绾胯窇鍚屼竴涓姤鍛婏紙涓嶅惎鍔ㄦ父鎴忥級锛?
```powershell
pwsh -File tools/build.ps1
& "C:\Program Files\Java\jdk-21\bin\java.exe" -cp build\libs\OptiForge-0.1.0.jar `
  kynarain.cn.optiforge.Diagnose --jar mods\OptiFine_1.21_HD_U_J1_pre9.jar --forge 51.0.33 --mc 1.21
```

## 宸ョ▼缁撴瀯

| 璺緞 | 鍐呭 |
|---|---|
| `src/main/java/kynarain/cn/optiforge/` | ModLauncher 鍙樻崲鏈嶅姟 + 璇婃柇锛堣涓嬭〃锛?|
| `src/main/resources/META-INF/services/cpw.mods.modlauncher.api.ITransformationService` | 璁?Forge 鍦?`mods/` 閲屽彂鐜版湰 jar 鐨勫敮涓€鍑嵁 |
| `src/main/resources/optiforge/optifine-forge-matrix.csv` | 鎵撹繘 jar 鐨勫吋瀹硅〃锛堢敱 `data/` 鍚屾锛?|
| `data/optifine-forge-matrix.csv` | 鍏煎琛ㄧ殑婧愭枃浠讹紝645 琛岋細379 鍏煎 / 90 N/A / 176 鏃犳暟鎹?|
| `tools/build-matrix.ps1` | 浠?BMCLAPI 閲嶆柊鐢熸垚鍏煎琛?|
| `tools/fetch-deps.ps1` 路 `tools/build.ps1` | 鍙?ModLauncher/ASM銆佺敤 javac 鏋勫缓 |
| `docs/` | 鏈哄埗璋冪爺銆佺増鏈煩闃点€佸綋鍓嶇姸鎬?|

浠ｇ爜鍙湁 6 涓被锛岃亴璐ｅ崟涓€锛?
| 绫?| 鑱岃矗 |
|---|---|
| `OptiForgeService` | `ITransformationService` 瀹炵幇锛沗initialize` 閲屽仛璇婃柇锛宍transformers()` 鐩墠涓虹┖ |
| `RuntimeInfo` | 浠?`sun.java.command` 璇?`--fml.mcVersion` / `--fml.forgeVersion` / `--gameDir` |
| `OptifineJar` | 璇?zip 寰楀埌琛ヤ竵涓庤嚜鏈夌被娓呭崟锛堜笉纰颁换浣?Forge/Minecraft 绫诲瀷锛?|
| `ForgeMatrix` | 瀹樻柟 Forge 鍏煎琛?+ 鐗堟湰姣旇緝锛堝鐞?`Forge #2847` 杩欑鏃х紪鍙凤級 |
| `PatchPlan` | 鐢?jar 娓呭崟 + 琛ㄨ鎺ㄥ"瑕佷笉瑕佷慨銆佸彲鑳戒慨鍝噷" |
| `Diagnose` | 娓叉煋鎶ュ憡锛涘甫 `main()` 鍙绾胯繍琛?|

## 鏋勫缓

```powershell
pwsh -File tools/build.ps1          # javac + jar锛屽凡楠岃瘉
pwsh -File tools/check.ps1          # 楠岃瘉 Forge 浼氬彂鐜拌繖涓?jar锛堢绾匡紝涓嶅惎鍔ㄦ父鎴忥級
.\gradlew build                     # 鍚屼竴浠戒骇鐗╋紝Gradle 鐗堬紙棣栨瑕佷笅杞藉彂琛屽寘锛?```

鐩爣 Java 17锛坈lass file 61锛夛紝涓€浠?jar 鍚屾椂缁?1.20.x(Java 17) / 1.21.x(21) / 26.x(25) 鐢ㄣ€?
## 璁捐瑕佺偣锛堣鎯呰 [docs/DESIGN.md](docs/DESIGN.md)锛?
- **Forge 涓嶆妸 OptiFine 褰?mod**锛歚mods/` 閲屼换浣曞０鏄庝簡 `cpw.mods.modlauncher.api.ITransformationService`
  鐨?jar 閮借 `ModDirTransformerDiscoverer` 鏀朵綔"鍙樻崲鏈嶅姟"骞朵粠鏅€?mod 鍙戠幇閲屾帓闄ゃ€傛墍浠ユ湰宸ョ▼**娌℃湁
  `mods.toml`銆佹病鏈?`@Mod`** 鈥斺€?瀹冨拰 OptiFine 璧板悓涓€涓叆鍙ｃ€?- **涓嶇紪璇戜緷璧?Minecraft/Forge**锛屽彧渚濊禆 ModLauncher API 涓?ASM锛屽洜姝や笉闇€瑕?ForgeGradle銆佷笉闇€瑕佹瘡鏉＄嚎涓€涓伐绋嬨€?- **鍏煎琛ㄦ槸鏁版嵁锛屼笉鏄唬鐮?*锛歄ptiFine 鐨?Forge 鏀寔鏄€愭瀯寤哄０鏄庣殑锛屾墍浠ュ垽瀹氶€昏緫璇昏〃銆?