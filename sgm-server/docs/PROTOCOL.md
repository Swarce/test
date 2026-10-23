# Skullgirls Mobile backend protocol (client 8.11.0)

Everything here was recovered from the Android client (Unity 2022.3.62f2, IL2CPP, metadata v31).
Class/method names refer to the client's code; see `ENDPOINTS.md` for the full request list.

## Transport

| | |
|---|---|
| API hosts | `https://skgm-prod-game-api.skullgirlsmobile.com` (prod); `skgm-{dev,qa,test}-game-api.skgminternal.com` |
| CDN | `https://skgm-prod-cdn.skullgirlsmobile.com/v1/` — asset bundles only (`vram.bytes`, `fingerprints.bytes`, `Android/platform_fingerprints.bytes`, bundles) |
| Base path | `/v1/<endpoint>` (`ClientAPI.GetUrl`) |
| HTTP stack | .NET `HttpWebRequest` (`HttpWebRequestWrapper`), keep-alive, `Expect: 100-continue` |
| Bodies | MiniJSON; `Content-Type: application/json`; numbers come back as `long`/`double` |
| Verbs | `HttpVerb`: Get, Post, Put, Delete (Create/Head unused) |

Request headers:

* `X-SG-TRANS-ID` — `<server time ms><counter>`, per request fingerprint (debug only).
* `Cookie` — every `Set-Cookie` from `authenticate` is replayed on all later calls
  (`AuthenticateRequest.ParseSetCookieHeaders` applies the regex `(.+?)=(.+?);` to each
  comma-separated part, so send `name=value; Path=/`).
* `Accept-Language` — locale, set after login.
* `X-SG-SIG` — only on signed endpoints (below).

Response requirements:

* Top level must be a JSON **object**; payloads live under `"data"` (object or list).
* `Date` header: the client syncs its clock from it (`NetworkRequest.UpdateServerTimeFromResponse`).
* Errors: any non-2xx with `{"errorCode": "<code>", "message": "..."}`
  (`NetworkRequest.FinishRequest`). HTTP 403 sends the player back to the login screen.
* Base64: requests created with `expectedResponseEncoding = Base64` decode the body before
  parsing (falling back to plain text with a warning). Used by `fight` (prep/start/end), skill
  node purchase and the leaderboard-event fight endpoints.

### Signatures

```
secret = "k5fh3Xx+Dk{;h*YR]pHRs-$ya*XeaT)Z[ukdf-7Vk]vb53S;L<992+t%)SCpP[C,"   // ClientAPI..cctor
request  X-SG-SIG = HEX( SHA256( UTF8("/v1/" + endpoint + concat(sorted(queries))) + UTF8(body) + UTF8(secret) ) )
response X-SG-SIG = HEX( SHA256( UTF8(decoded body) + UTF8(secret) ) )        // compared case-insensitively
```

`HEX` is upper case (`ByteUtil.ToHex`, `"X2"`); queries are `"key=value"` strings sorted with
`String.Compare`. Signed endpoints: `fight`, `fight/{id}/start`, `fight/{id}/end`,
`character/{id}/skilltree/...`, `character/{id}` (GET), `user/publicid/{id}`,
`fight/leaderboardEvent/{id}/refresh|restoreStreak`. The client rejects signed responses whose
signature does not match.

### TLS

`SGInitialize.Init` installs `HttpsHelper.MyRemoteCertificateValidationCallback` as
`ServicePointManager.ServerCertificateValidationCallback`, which rebuilds the chain with
`X509VerificationFlags.AllFlags` and accepts it. That callback governs the legacy Mono
`HttpWebRequest`/`ServicePointManager` path.

**However**, on the 8.11.x Android build the HTTPS requests validate through Unity's TLS, which
bridges to Android's Java `X509TrustManager` (`TrustManagerFactory` / `getTrustManagers` /
`getAcceptedIssuers` in `libunity.so`) and does **not** honour that callback. A self-signed
certificate is rejected at the handshake (`TLSV1_ALERT_ACCESS_DENIED`). So a redirected server
must present a certificate that chains to a CA in the device's **system** trust store — i.e. you
install a CA (the Android manager runs one for this; see `../../sgm-manager`). A DNS redirect
alone is not enough on this build. User-installed CAs do not count on Android 7+ unless the app
ships a network-security-config, which this game does not.

## Authentication

`POST authenticate/{platform}/{deviceId}/{regionCode}/{LoginType}` (`LoginType`: Guest, Facebook,
Google, LINE, Password, Apple, Huawei)

* Guest: `X-AUTH-USER-TOKEN: <saved guestAuthToken>` when one is stored.
* Password: `Authorization: Bearer base64("<email>:<password>")`.
* Body: `ddnaMetricsId, newUser, deviceMetrics, deviceMetricsContext, integrityToken` (+`accountName`); query `tag`.

Response `data`: `{session:{userId, providerUserToken, isGuest, isNewUser}, user: UserServerData, loginRewardCalendarStates: []}`.
The client stores `providerUserToken` as its guest token.

## Core data shapes

**CurrencyBalance** (`hardCurrency`, `softCurrency`): `{paid, free, bonus}`.

**Price**: `{"amounts": {"SoftCurrency": n, ...}}`.

**UserServerData** (`GET user`, auth): `id, publicId, handle, level, floatLevel, experience,
tutorialProgress, completedTutorials (TutorialType bitmask), tutorialProgressMap {TutorialType name: progress},
hardCurrency, softCurrency, otherCurrencies [{type, amount}], skillPoints [{guid: baseCharacterGuid, amount}],
rerollRunes [...], consumables [{guid, count, isNew}], items [{guid: gachaGuid, count}], teamIds [],
characters [...], specialMoves [...], assistMoves [...], nodeModifiers [...], variantsEverOwned
[{guid, variantsEverOwned, variantsEverOwnedShiny}], *CollectionCapacity/*CollectionCount, repCharacterId,
cosmeticsInstance {portraitsOwned, newPortraits}, claimableChallengeCount, challengeExpirationDate, battlePass*`,
optional `guild {...}`. `otherCurrencies.type` names: `BronzeKey..DiamondKey, Silver/Gold/DiamondGachaShard,
CharacterGachaShard, <Element>Essence, <Element>EssenceShard, DailyEventTicket, BattleTicketFree/Paid, ...`.
TutorialType: FTUE=1 (cleared at progress 8), MoveEquip=2, SkillTree=4, RiftBattle=8, XPBoost=16, Maze=32,
Pity=64, RaidBoss=128, AutoBattle=256, PrizeFight=512, MissionControl=1024.

**CharacterServerData**: `id, variantGuid, tier, experience, floatLevel, skillNodeGuids [], superAbilityLevels [],
superAbilityActiveFeatureSet, prestigeAbilityLevel, energy, nextEnergyTimestamp, energyMp, nextEnergyTimeMpStamp,
lifebar, attack, specialMoveIds [], assistMoveInstanceId, isLocked, isNew, isShiny, isUsedInDeployment`.

**GearServerData** (moves): `id, moveGuid, evolution (= move level 1..15), saleValue (Price),
moveStatInstances [{guid, level}], isLocked, isNew, rerolledMoveStat {originalMoveStatGuid, newMoveStatGuid}`.

### Rewards

`RewardServerData`: `{"key": {"type": RewardOrigin, "associatedId": str}, "rewards": [item, ...]}`.
RewardOrigin: Fight, Match, Chapter, FullChapter, Act, FullAct, Random, Gacha, LevelUp, CharacterLevelUp,
CharacterExperienceGain, CharacterFeed, LeaderboardEvent, RaidBossEvent, MatchChallenge, ...

Items are `{"type": RewardType, "<kind>Reward": {...}, "isBonus"?, "price"?}`
(`RewardLootUtil.ParseLootFromRewardDict`):

| RewardType | key | payload |
|---|---|---|
| Currency | `currencyReward` | `{amounts: {CurrencyName: n}}` |
| Character | `characterReward` | `{variantGuid, characterInstanceId, isShiny}` (+ `isFirstTimeAppearance`) |
| Move / AssistMove | `moveReward` / `assistMoveReward` | `{moveGuid, moveInstanceId, statGuids}` |
| Gacha | `gachaReward` | `{guid, count}` |
| Consumable | `consumableReward` | `{guid, count}` |
| BaseCharacterCurrency | `baseCharacterCurrencyReward` | `{baseCharacterGuid, amounts: {SkillPoint|RerollRune: n}}` |
| NodeModifier | `nodeModifierReward` | `{guid, characterGuid, element, nodeModifierInstanceId}` |
| CharacterXp | `characterXpReward` | `{instanceId, total, gained, level, from, to}` (from/to = float levels) |
| PlayerXp | `playerXpReward` | same as above |

Mail/claimables use the `claimable<Kind>Reward` keys.

## Campaign and fights

* `GET acts/campaign` → `data: [actGuid]`.
* `PUT acts/progress {descriptors: [{guid, eventInstanceId}]}` → `data: [ActServerData]`:
  `{guid, chapters: [{guid, matches: [{guid, isFinal, isCompleted, matchChallengesProgress (bitmask),
  fights: [{enemies: [XsCharacter]}]}]}]}`; `XsCharacter = {variantGuid, tier, fighterScore, isShiny,
  isSuperAbilityUnlocked, isPrestigeAbilityUnlocked}` (map previews).
* `POST fight {type: GameType name, actGuid, chapterGuid, matchGuid, eventInstanceId, ...}` (signed, base64)
  → `data: {fights: [FightInstanceServerData]}`; `FightInstanceServerData = {id, difficulty, opponentId,
  enemyFighterFSMultiplierAddition, enemyName, enemyIsDeveloper, defenseBonusCount, characters:
  [EnemyCharacterServerData]}`; `EnemyCharacterServerData = {guid (variant), level, tier, lifebar, attack,
  fighterScore, isRandom, isShiny, specialMoveInstances [GearServerData], skillNodeGuids, superAbilityLevels,
  superAbilityActiveFeatureSet, prestigeAbilityLevel}`.
* `PUT fight/{id}/start {teamIds}` (signed, base64) → `data: {uploadReplay}`.
* `PUT fight/{id}/end?status=won|lost|conceded {stats, replayData, lastEnteredChatChannel}` (signed, base64)
  → `data: {rewards: [RewardServerData], matchChallengesResults (bitmask), publicReplayId, eventResult?}`.
  `stats` entries are `{type, params, count}`; `MatchChallenge` entries carry the challenge index in
  `params[0]` and progress in `count`.

Content: `Act.chapterResources` name `Chapter` objects (or `Act.chapters`), `Chapter.matches` →
`SGMatch.fights` → `Fight` (`enemyCharacterGenerators`, `matchChallenges`, `rewardMultiplier`,
`xpMultiplier`, `xpOverride`, `energyRequirement`). GameType: Campaign=0, LimitedTimeAct=7, LeaderboardEvent=8,
RiftBattle=9, Maze=10, RaidBoss=11.

## Events

`GET events/{region}?type=EventsFilter` → `data: {activeEvents: [...], upcomingEvents: [...]}`; each entry
has `type` (`LimitedTimeAct`, `LeaderboardEvent`, `RiftBattle`, `Maze`, `RaidBoss`, `GuildOps`, `SyncPvpEvent`)
plus `EventServerData` fields `guid, eventInstanceId, hasEntered, scheduleInstance {startDate, endDate}, score,
winStreak, rating, tier, defenseTeamIds, defenseHistory, wasEverFullyCompleted` and, for LTAs, `actGuid, playCount`.
`PUT events/{id}/enter` (costs `EconomySettings.playDailyEventPrice`), `POST events/{id}/skip` → `data: [RewardServerData]`,
`PUT events/claimFreeEventTickets/{type}` → `data: {rewardList, freeEventTicketResetDate}`.

## Relics

`GET store/gachas/{region}` → `data: {gachas: [{gachaGuid, isEarnOverTime, timeLeftToEarn, schedulingStatus
{currentlyActive, startDate, endDate}}]}`. `POST store/gacha/buy/{guid}?count&openAll`, `PUT user/gacha/open/{guid}?count`
→ `data: {rewards: [reward item per relic], pity?}`. Relic contents: weighted `GachaData.lootTableSets`, then one
roll per `LootTable` in the chosen set.

## Daily challenges

`GET challenges` → `data: {challenges: [{challengeGuid, count, status ("Claimed"|...), constraints}],
claimedRewards: [bool per threshold], expirationDate, newChallengesAvailable}`; `PUT challenge/{guid}/claim`;
`PUT challenges/reward/{index}/claim/{bool}` → `data: [RewardServerData]`. Pool: `ChallengeManager.challengesDatas`;
threshold `i` unlocks at `(i+1) * 100 / len(EconomySettings.challengesThresholdRewards)` claimed points.
Any response may carry a root-level `challenges` list to push progress (`ChallengeManager.OnResponseDictionaryReceived`).

## Game math (float32 in the client)

* **Character XP ladder** (`CharacterCalcUtil.IterateLevelLadder`): `inc(2) = baseLevelUpXpPerTier[tier]`;
  `inc(L) = round(inc(L-1) * nextLevelXpMultiplier * levelingMultiplierPerTier[tier])`, except levels listed in
  `xpResetThresholds` where `inc(L) = round(base * baseLevelUpXpMultiplier)`; XP to reach L is the cumulative sum;
  shiny characters need `ceil(x/2)`. Level caps (`Character.kMaxCharacterLevels`): 30/40/50/60.
* **Player XP ladder**: same shape with `baseLevelUpPlayerXp`, `nextLevelPlayerXpMultiplier * playerLevelingMultiplier`,
  `playerXpResetThresholds`.
* **Stats**: `stat = ceil((base + base*(level-1)*levelScaleFor{Lifebar|Attack}) * (1 + buff))`, base from
  `VariantCharacterData.baseScaledValuesByTier[tier - initialTier]`, `buff = 0.01 * Σ value` of purchased skill
  nodes with nodeType Lifebar(4)/Attack(5).
* **Fighter score**: `ceil((lifebar/6 + attack) * (1 + m) * overallFighterScoreMultiplier)` with
  `m = Σ node.fsMultiplierAddition * skillNodeFSGlobalMultiplier + super/prestige terms`.
* **Fight XP** (`CalculateTeamExperienceGainedFromFight`): `enemyTeamFS * longshot * teamXpMultiplier`,
  `longshot = floor(min(2 - playerFS/enemyFS, 2) * 10) / 10` when the player is weaker.
* **Feeding** (`EconomySettings.CalculateFeedingXp`): `feedingXpByTier[foodTier] + feedingMultiplierOfTotalXp *
  min(foodXp, capXp)`, +25% same element, +25% same base character; feeding also returns
  `(levelCap - foodLevel) * skillPointsOnCharacterLevelUp * skillPointTierMultipliers[tier]` skill points.
* **Skill nodes**: SkillPoint cost `skillPointCost * skillPointTierMultipliers[tier]` of the base character plus
  `cost * skillNodeSCCostTierMultipliers[tier]` (keys are not scaled); shortcut: `shortcutCost * skillNodeHCCostTierMultipliers[tier]`.
* **Enemy generation** (`CharacterGenerator.BuildCharacterFromData`): tier = max(initialTier, minCharacterTier),
  level = random in levelRange clamped to cap, skill points `perLevel*(level-1) + extraSkillPoints` spent along
  `GameSettings.aiSkillTree.purchaseOrder`.
