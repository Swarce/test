# Client API endpoints (Skullgirls Mobile 8.11.0)

Recovered from the Android client's `*Request` classes. All paths are relative to `/v1/`. "Server" marks endpoints this server implements; others fall back to an empty `{}` response and are logged as `UNHANDLED`.

| Request class | Verb | Path | Body params | Query params | Notes | Server |
|---|---|---|---|---|---|---|
| ActivatePremiumBattlePassRequest | PUT | `debug/battlePass/activatePremium/{…}` |  |  |  |  |
| ActivePremiumPlusBattlePassRequest | PUT | `debug/battlePass/activatePremiumPlus/{…}` |  |  |  |  |
| AdEnforceRewardsRequest | POST | `ads/{…}/enforceRewards?custom_data={…}` |  |  |  |  |
| AdPlacementInfoRequest | GET | `ads/{…}/info` |  |  |  |  |
| AdsInfoRequest | GET | `ads/info` |  |  |  | yes |
| AdsPlacementCollectRequest | POST | `ads/{…}/collect` |  |  |  |  |
| AuthenticateRequest | POST | `authenticate/{platform}/{deviceId}/{region}/{LoginType}` | accountName, ddnaMetricsId, deviceMetrics, deviceMetricsContext, integrityToken, newUser | tag |  | yes |
| BindAccountRequest | PUT | `authenticate/bind/{…}` | email, password |  |  |  |
| BlockRequest | PUT | `friends/block` | id |  |  |  |
| BuyBattleTicketRequest | PUT | `events/riftBattle/buyTicket` |  |  |  |  |
| BuyRaidBossTicketRequest | PUT | `raidBoss/tickets/ / raidBoss/tickets/{…}` |  |  |  |  |
| ChangeEmailRequest | PUT | `authenticate/email/change` | email, password |  |  |  |
| ChangeHandleRequest | PUT | `user/handle / user/handle{…}` | handle |  |  | yes |
| CheckGuildHandleAvailabilityRequest | PUT | `guildNameAvailability / guildNameAvailability{…}` | name |  |  |  |
| CheckGuildTagAvailabilityRequest | PUT | `guildTagAvailability / guildTagAvailability{…}` | tag |  |  |  |
| CheckHandleAvailabilityRequest | PUT | `user/checkHandleAvailable / user/checkHandleAvailable{…}` | handle |  |  | yes |
| ChooseMazeCollectibleRequest | POST | `events/mazeEvent/{…}/chooseCollectible/{…}` |  | isArtifact |  |  |
| ClaimAccountBindingRewardsRequest | PUT | `user/accounts/claimReward/{…}` |  |  |  |  |
| ClaimAchievementRequest | POST | `achievement/{…}/claim` |  |  |  |  |
| ClaimAdRewardsRequest | GET | `ads/claim` |  |  |  |  |
| ClaimAdStoreOfferRewardsRequest | POST | `ads/offers/{…}/claim` |  |  |  |  |
| ClaimAllBattlePassChallengesRequest | PUT | `battlePass/claimAllChallenges` |  |  |  |  |
| ClaimAllBattlePassRewardsRequest | POST | `battlePass/claimAllRewards` |  |  |  |  |
| ClaimAllGuildGiftRequest | POST | `guildGifts/claimAll` |  |  |  |  |
| ClaimAllOrdersRequest | POST | `aghanim/directPurchase/claimAll` |  |  |  |  |
| ClaimBattlePassChallengeRequest | PUT | `battlePass/claimChallenge/{…}` |  |  |  |  |
| ClaimBattlePassRewardRequest | POST | `battlePass/claimReward/{…}/{…}` |  |  |  |  |
| ClaimChallengeRequest | PUT | `challenge/{…}/claim` |  |  |  | yes |
| ClaimChallengesRewardRequest | PUT | `challenges/reward/{…}/claim/{…}` |  |  |  |  |
| ClaimDefenseHistoryPointsRequest | PUT | `events/leaderboardEvent/{…}/claimPoints` |  |  |  |  |
| ClaimDeploymentRequest | PUT | `deployments/{…}/claim` | completionTimestamp | hurryUp |  |  |
| ClaimFreeEventTicketsRequest | PUT | `events/claimFreeEventTickets/{…}` |  |  |  | yes |
| ClaimGuildChallengesRewardRequest | PUT | `guildChallenges/claimAll` |  |  |  |  |
| ClaimGuildGiftRequest | POST | `guildGifts/{…}/claim` |  |  |  |  |
| ClaimGuildOpsChallengeRequest | PUT | `guildChallenges/{…}/claim` |  |  |  |  |
| ClaimMailRequest | PUT | `mail/claim/{…}` |  |  |  |  |
| ClaimOrderRequest | POST | `aghanim/directPurchase/claim/{…}` | orderId |  |  |  |
| CompleteMazeNonBattleNodeRequest | PUT | `events/mazeEvent/{…}/nonBattleNode/{…}` |  |  |  |  |
| ConfirmRerolledMoveStatRequest | POST | `move/{…}/confirmRerolledStat/{…}` |  | isAssist |  |  |
| ConsumeConsumableRequest | PUT | `consumable/{…}/consume` | grantXPBoostTutorialRewards |  |  |  |
| ConvertMaxTierCharacterRequest | POST | `character/{…}/convert` |  |  |  | yes |
| ConvertShardGachaRequest | POST | `store/gacha/convertToShards/{…}` |  |  |  | yes |
| CreateGuildRequest | POST | `guilds` | emblem, joinRequirement, message, name, privacy, tag, userInactivityKickDays |  |  |  |
| DebugChooseMazeMapRequest | PUT | `(computed)` |  |  |  |  |
| DebugResetDeploymentsStartedTodayRequest | PUT | `deployments/debug` |  |  |  |  |
| DeleteMailRequest | DELETE | `mail/{…}` |  |  |  |  |
| DisbandGuildRequest | DELETE | `guilds` |  |  |  |  |
| EditGuildMemberRoleRequest | PUT | `guildMembers/{…}/{…}` |  |  |  |  |
| EditGuildRequest | PUT | `guilds` | dailyMessage, emblem, joinRequirement, message, name, privacy, tag, userInactivityKickDays |  |  |  |
| EndFightRequest | PUT | `fight/{…}/end` | lastEnteredChatChannel, replayData, stats | status | signed base64 | yes |
| EnterEventRequest | PUT | `events/{…}/enter` |  |  |  | yes |
| EquipGearRequest | PUT | `user/equip, user/competitive/equip` |  | aId |  | yes |
| EvolveCharacterRequest | PUT | `character/{…}/evolve` |  | fIds |  | yes |
| EvolveSpecialMoveRequest | PUT | `move/{…}/evolve, assistMove/{…}/evolve` |  | levels |  | yes |
| ExpandCollectionRequest | PUT | `user/collection/expand/{…}` |  |  |  |  |
| FakeOpenGachasRequest | PUT | `debug/gacha/fake_open/{…}` |  | count |  |  |
| FlagAccountForDeletionRequest | PUT | `user/flagAccountForDeletion/{…}` |  |  |  | yes |
| ForgotPasswordRequest | POST | `authenticate/password/forgot` | email |  |  |  |
| FriendRequestActionRequest | DELETE/POST/PUT | `friends / friends/requests/{…}/send` |  | friendIds |  |  |
| GetAccountsRequest | GET | `user/accounts` |  |  |  | yes |
| GetAchievementsRequest | GET | `achievements` |  |  |  | yes |
| GetActsProgressRequest | PUT | `acts/progress` | descriptors |  |  | yes |
| GetAllActiveOffersRequest | GET | `offers/{…}` |  |  |  | yes |
| GetAvailableCampaignActsRequest | GET | `acts/campaign` |  |  |  | yes |
| GetAvailableDeploymentsRequest | GET | `deployments` |  | reset |  | yes |
| GetAvailableGachasRequest | GET | `store/gachas/{…}` |  |  |  | yes |
| GetBattleInstanceRequest | GET | `events/riftBattle/{…}/get/{…}` |  |  |  |  |
| GetBattlePassDataRequest | GET | `battlePass` |  |  |  | yes |
| GetCalendarStatesRequest | GET | `user/calendars` |  |  |  | yes |
| GetChallengesRequest | GET | `challenges` |  |  |  | yes |
| GetCharacterRequest | GET | `character/{…}` |  |  | signed |  |
| GetChatInfoRequest | GET | `chat/info` |  |  |  | yes |
| GetCollectionRequest | GET | `user/collection` |  | dataFlags |  | yes |
| GetCosmeticsRequest | GET | `user/cosmetics` |  |  |  | yes |
| GetCurrentTeamRequest | GET | `user/team` |  |  |  | yes |
| GetCurrentUserRequest | GET | `user` |  | dataFlags |  | yes |
| GetDirectPurchaseStoreRequest | GET | `directPurchase` |  | rerollAllowed |  | yes |
| GetEventsRequest | GET | `events/{…}` |  | type |  | yes |
| GetFriendInstancesRequest | GET | `friends` |  |  |  | yes |
| GetGachaPityHLRequest | GET | `user/headlinerGachaPity` |  |  |  | yes |
| GetGachaPityRequest | GET | `user/premiereGachaPity` |  |  |  | yes |
| GetGearLoadoutsRequest | GET | `loadouts/{…}` |  |  |  | yes |
| GetGlobalSettingsRequest | GET | `{…}/global_settings` |  |  |  | yes |
| GetGuildClaimRewardsRequest | PUT | `guildOps/claimRewards` |  |  |  |  |
| GetGuildFriendsRequest | GET | `guildFriends` |  |  |  | yes |
| GetGuildGiftsRequest | GET | `mails` |  | count, locale, page |  | yes |
| GetGuildInvitesRequest | GET | `guildInvites/{…}` |  |  |  |  |
| GetGuildJoinRequestsRequest | GET | `guildInvites` | count, page |  |  |  |
| GetGuildLeaderboardInfoRequest | GET | `guildLeaderboard/{…}` |  |  |  |  |
| GetGuildMembersRequest | GET | `guildMembers` |  | count, orderBy, page |  |  |
| GetGuildOpsChallengesRequest | GET | `guildChallenges` |  |  |  | yes |
| GetGuildOpsProgressRequest | GET | `guildOpsProgress` |  |  |  | yes |
| GetGuildRaidBossClaimMilestoneRequest | PUT | `raidBoss/milestones/{…}/claim` |  |  |  |  |
| GetGuildRaidBossClaimRequest | GET | `rewardsRaidBoss` |  |  |  |  |
| GetGuildRaidBossClaimSeasonRewardsRequest | PUT | `raidBoss/season/claim` |  |  |  |  |
| GetGuildRequest | GET | `guilds/{…}` |  |  |  |  |
| GetGuildsRequest | GET | `guilds` |  | count, page, recommended, search, type |  | yes |
| GetIAPPurchaseStatus | GET | `billing/purchase/status` |  | orderId |  | yes |
| GetLeaderboardGuildsRequest | GET | `guildLeaderboard/{…}/items` |  | count, startRank |  |  |
| GetMailRequest | GET | `mails` |  | count, locale, page |  | yes |
| GetMazeInstanceRequest | GET | `events/mazeEvent/{…}/mazeInstance` |  |  |  |  |
| GetOwnedGachasRequest | GET | `user/gachas` |  |  |  | yes |
| GetRaidbossProgress | GET | `raidBoss/progress` |  |  |  | yes |
| GetReplayRequest | GET | `replays/{…}/{…}` |  |  |  |  |
| GetReplaysRequest | GET | `replays` |  | actGuid, chapterGuid, eventInstanceId, matchGuid, userIdSearch |  | yes |
| GetRiftBattleDefenseHistoryPlayerRequest | GET | `(computed)` |  |  |  |  |
| GetRiftBattleDefenseHistoryRequest | GET | `events/riftBattle/{…}/defenseHistory` |  |  |  |  |
| GetRiftBattleRewardsRequest | POST | `events/riftBattle/{…}/getRewards/{…}` |  |  |  |  |
| GetRiftBattleStateRequest | GET | `events/riftBattle/{…}/battle` |  |  |  |  |
| GetUnreadMailCountRequest | GET | `mail/count/unread` |  | locale |  | yes |
| GetUpdatesRequest | GET | `user/updates` |  |  |  | yes |
| GetUserByPublicIdRequest | GET | `user/publicid/{…}` |  |  | signed | yes |
| GetUserGuildRequest | GET | `user/guild` |  |  |  | yes |
| GetWebStoreCalendarRequest | GET | `user/webStoreCalendar` |  |  |  | yes |
| GetXPBoostTutorialRewardsRequest | GET | `user/tutorial/reward/xpboosts` |  |  |  | yes |
| GuildInvitationReplyRequest | POST/PUT | `(computed)` | ids |  |  |  |
| JoinGuildRequest | POST | `guilds/{…}/join` | message |  |  |  |
| KickGuildMemberRequest | DELETE | `guildMembers/{…}` |  |  |  |  |
| LeaveGuildRequest | DELETE | `user/guild` |  |  |  |  |
| MarkChallengesSeenRequest | PUT | `user/challenges/seen` |  |  |  | yes |
| MarkPortraitsSeenRequest | PUT | `user/cosmetics/markPortraitsSeen` |  |  |  | yes |
| OpenGachasRequest | PUT | `user/gacha/open/{…}` |  | count |  | yes |
| OpenSocialGiftsRequest | POST | `gifts/open` |  |  |  |  |
| OrderStatusRequest | GET | `aghanim/directPurchase/status/{…}` | orderId |  |  |  |
| PingRequest | GET | `ping` |  |  |  | yes |
| PostAuthUpdatesRequest | POST | `user/postLogin/updates` | adIds | locale |  | yes |
| PowerupCharacterRequest | PUT | `character/powerUpCharacter` | consumablesToConsumeMap | cId, fIds |  | yes |
| PrepFightRequest | POST | `fight` | actGuid, chapterGuid, eventInstanceId, isPrivateMatch, matchGuid, mazeMapNodeIndex, opponentPublicId, raidBossMatchGuid, type |  | signed base64 | yes |
| PrizeFightAroundMeRequest | GET | `prizefights/aroundMe?eventInstanceId={…}&userId={…}&radius={…}` |  |  |  |  |
| PrizeFightPageRequest | GET | `prizefights/page?eventInstanceId={…}&league={…}&offset={…}&limit={…}` |  |  |  |  |
| PrizeFightRankRequest | GET | `prizefights/rank?eventInstanceId={…}&userId={…}` |  |  |  |  |
| PurchaseConsumableRequest | POST | `consumable/{…}/{…}/purchase` |  |  |  |  |
| PurchaseCurrencyStoreOfferRequest | POST | `offers/currency/{…}/purchase` |  | isLTO, isUserOffer |  |  |
| PurchaseDirectPurchaseOfferRequest | POST | `directPurchase/{…}/{…}/purchase` |  |  |  |  |
| PurchaseGachaRequest | POST | `store/gacha/buy/{…} / store/gacha/voucherBuy/{…}` |  | count, openAll |  | yes |
| PurchaseSkillNodeRequest | PUT | `character/{…}/skilltree/buy/{…}` | shortcut |  | signed base64 | yes |
| ReadMailRequest | PUT | `mail/read/{…}` |  |  |  |  |
| RecycleRequest | POST | `user/collection/queryRecycleInfo, user/collection/recycle` | assistMoves, characters, moves, nodeModifiers |  |  | yes |
| RefillEnergyRequest | PUT | `characters/refill` |  | adRefill, ids |  | yes |
| RefreshCabinetAdRequest | GET | `directPurchase` |  |  |  | yes |
| RefreshDeploymentRequest | PUT | `deployments/{…}/refresh` |  |  |  |  |
| RefreshDirectPurchaseStoreRequest | POST | `directPurchase/refresh` |  |  |  | yes |
| RefreshLeaderboardEventFightsRequest | PUT | `fight/leaderboardEvent/{…}/refresh` | type |  | signed base64 |  |
| RefreshMazeMapRequest | GET | `events/mazeEvent/{…}/map` |  |  |  |  |
| RefreshPrizeFightOpponentsAdRequest | GET | `fight/leaderboardEvent/{…}` | type |  | base64 |  |
| RefreshPubNubAccessTokenRequest | GET | `chat/pubnub/accessToken` |  |  |  | yes |
| RefreshPubNubGuildChatAccess | GET | `guildChatInfo` |  |  |  | yes |
| RefreshRiftBattleOpponentsRequest | POST | `events/riftBattle/{…}/refresh` |  | useHC |  |  |
| RenameGuildRequest | PUT | `guildName / guildName{…}` | name, tag |  |  |  |
| ReportChatAbuseRequest | POST | `user/{…}/report/chat` | channelId, messageHistory, reportReasons, reportedMessage |  |  |  |
| ReportCheaterRequest | POST | `user/{…}/report/cheater` | attackingCharacters, defendingCharacters, replayId, reportReasons, reportType |  |  |  |
| ReportGuildRequest | POST | `guilds/{…}/report` | details, id, message, name, reason, tag |  |  |  |
| ReportRequest | POST | `user/report` | reason |  |  |  |
| RerollGearStatsRequest | PUT | `debug/rerollMoveStats/{…}` |  | isAssist |  |  |
| RerollSingleGearStatRequest | POST | `move/{…}/rerollStat/{…}` |  | isAssist |  |  |
| ResendVerificationRequest | POST | `authenticate/verify/resend` |  |  |  |  |
| ResetFreeTicketRequest | POST | `debug/currency/resetFreeBattleTickets` |  |  |  |  |
| ResetLTAProgressRequest | PUT | `debug/resetLTAProgress/{…}` |  |  |  |  |
| RestoreLeaderboardEventStreakAdRequest | GET | `fight/leaderboardEvent/{…}` | type |  | base64 |  |
| RestoreLeaderboardEventStreakRequest | PUT | `fight/leaderboardEvent/{…}/restoreStreak` | type |  | signed base64 |  |
| SaveGearLoadoutRequest | POST | `loadouts/save` | loadoutId | aId, cGuid, mIds |  |  |
| SearchForFriendsRequest | GET | `friends/search/{…}` |  |  |  |  |
| SellGearRequest | PUT | `moves/sell` | ids | isAssist |  | yes |
| SellNodeModifierRequest | PUT | `nodeModifiers/sell` | ids |  |  | yes |
| SendAllGuildGiftsRequest | PUT | `guildGifts/sendAll` |  |  |  |  |
| SendChatMessageGuildsRequest | POST | `guildChatMessage` | accessToken |  |  |  |
| SendChatMessageRequest | POST | `chat/{…}` | accessToken |  |  |  |
| SendGuildGiftRequest | PUT | `guildGifts/{…}/send` | type |  |  |  |
| SendGuildInvitationRequest | POST | `guildInvites` | userId |  |  |  |
| SetAvatarRequest | PUT | `user/avatar/{…}` |  |  |  | yes |
| SetCharacterLockStateRequest | PUT | `character/{…}/lock` |  | enabled |  | yes |
| SetCharactersNewStateRequest | PUT | `characters/setNew` | ids | isNew |  | yes |
| SetConsumableNewStateRequest | PUT | `consumables/setNew` |  | guids, isNew |  | yes |
| SetCurrentTeamRequest | PUT | `user/team, user/competitive/team` |  | defensive |  | yes |
| SetDefenseTeamRequest | PUT | `matchmaking/{…}/team` |  | ids |  |  |
| SetEventMapDataRequest | PUT | `events/map/{…}` | nodes |  |  |  |
| SetEventMapNodeDataRequest | PUT | `events/map/{…}/{…}` |  | characterIds, modifierIds |  |  |
| SetGachaPityHLRequest | PUT | `user/headlinerGachaPity` | fighters, tier |  |  | yes |
| SetGachaPityRequest | PUT | `user/premiereGachaPity` | fighters, tier |  |  | yes |
| SetGearLockStateRequest | PUT | `move/{…}/lock` |  | enabled, isAssist |  | yes |
| SetGearNewStateRequest | PUT | `moves/setNew` | ids | isAssist, isNew |  | yes |
| SetLeaderboardEventStreakRequest | PUT | `debug/leaderboard/{…}/setStreak/{…}` |  |  |  |  |
| SetMazeTeamRequest | PUT | `events/mazeEvent/{…}/team` |  | ids |  |  |
| SetNodeModifierLockStateRequest | PUT | `nodeModifier/{…}/lock` |  | enabled |  | yes |
| SetNodeModifiersNewStateRequest | PUT | `nodeModifiers/setNew` | ids | isNew |  | yes |
| SetPlayerLevelRequest | PUT | `debug/playerLevel/{…}` |  |  |  |  |
| SetReplayFavoritedStateRequest | PUT | `replays/favorite/{…}` |  | favorited |  |  |
| SetTutorialProgressRequest | PUT | `user/tutorial` | abTestGroup, context, progress, tutorialType |  |  | yes |
| ShareReplayRequest | POST | `replays/share/{…}/{…}` |  |  |  |  |
| SkipBattlePassLevelRequest | POST | `battlePass/skipLevel` |  |  |  |  |
| SkipEventRequest | POST | `events/{…}/skip` |  |  |  | yes |
| StartCheckoutRequest | POST | `aghanim/directPurchase/checkout` | offerId |  |  |  |
| StartDeploymentRequest | POST | `deployments/{…}` | teamIds |  |  |  |
| StartFightRequest | PUT | `fight/{…}/start` | teamIds |  | signed base64 | yes |
| StartRiftBattleRequest | POST | `events/riftBattle/{…}/startBattle/{…}` |  | usePaidTicket |  |  |
| SwapOpponentRequest | POST | `debug/matchmake/{…}/swapOpponent` | contextId, gameType, handle |  |  |  |
| SwapSuperAbilityRequest | PUT | `character/{…}/super/swap` |  | feature |  | yes |
| SyncPvpPrefightCheckRequest | PUT | `events/syncpvp/prefight` |  | isCompetitive |  |  |
| TestFriendActionRequest | DELETE/POST/PUT | `friends/requests` |  | friendIds, userId |  |  |
| UnblockRequest | PUT | `friends/unblock` | id |  |  |  |
| UnlockSuperAbilityRequest | PUT | `character/{…}/super/unlock` |  | feature |  | yes |
| UpdateLoginChallengeRequest | PUT | `challenge/update/login` |  |  |  | yes |
| UpgradeNodeModifierRequest | PUT | `nodeModifier/{…}/upgrade` |  | levels |  |  |
| UpgradeSuperAbilityRequest | PUT | `character/{…}/super/upgrade` |  | feature |  | yes |
| ValidatePurchaseRequest | POST | `offers/money/validate` | grantViaMail, multiPurchaseCount, offerId, offerType, orderId, packageName, productId, receiptData, storeType |  |  |  |
| guildJoinReminderRequest | PUT | `user/guildJoinReminder` |  |  |  | yes |
