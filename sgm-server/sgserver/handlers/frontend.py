"""Main-menu services that must answer for the client to work, but whose live-ops content
(battle pass seasons, offers, mail, chat, guilds, friends, ads...) is not reproduced.

Every response keeps the container types the client's parsers expect, so menus open
empty instead of erroring.
"""
from ..server import route


# ---- challenges / achievements / calendars ---------------------------------------------------

@route('PUT', r'user/challenges/seen')
def challenges_seen(ctx):
    return {}


@route('GET', r'achievements')
def achievements(ctx):
    return {'data': []}


@route('GET', r'user/calendars')
def calendars(ctx):
    return {'data': []}


@route('GET', r'user/webStoreCalendar')
def webstore_calendar(ctx):
    return {'data': {}}


@route('GET', r'guildChallenges')
def guild_challenges(ctx):
    return {'data': {}}


# ---- battle pass ----------------------------------------------------------------------------

@route('GET', r'battlePass')
def battle_pass(ctx):
    far = ctx.now_ms + 30 * 86400000
    return {'data': {'battlePassGuid': '', 'battlePassPoints': 0, 'isPremiumActive': False,
                     'isPremiumPlusActive': False, 'claimedFreeRewards': [], 'claimedPremiumRewards': [],
                     'dailyChallenges': [], 'weeklyChallenges': [], 'monthlyChallenges': [],
                     'dailyExpirationDate': far, 'weeklyExpirationDate': far, 'monthlyExpirationDate': far,
                     'pointsWereAutoClaimed': False}}


# ---- store front (real-money / live-ops parts are empty) ----------------------------------------

@route('GET', r'offers/(?P<region>[^/]+)')
def offers(ctx, region):
    return {'data': {'featuredOffers': [], 'currencyStoreOffers': [], 'adStoreOffers': [], 'moneyStoreOffers': [],
                     'limitedTimeOffers': [], 'bundleStoreOffers': [], 'blockedMultiOfferGuids': [],
                     'globalOfferPopupCooldownHours': 1000, 'globalAdOfferPopupCooldownHours': 1000}}


@route('GET', r'directPurchase')
def direct_purchase(ctx):
    return {'data': {}}


@route('POST', r'directPurchase/refresh')
def direct_purchase_refresh(ctx):
    return {'data': {}}


@route('GET', r'user/premiereGachaPity')
def gacha_pity(ctx):
    return {'data': {'goldFighters': [], 'diamondFighters': [], 'bannedFighters': [], 'openedRelicsCount': 0}}


@route('GET', r'user/headlinerGachaPity')
def gacha_pity_hl(ctx):
    return {'data': {'goldFighters': [], 'diamondFighters': [], 'allowedFighters': [], 'openedRelicsCount': 0}}


@route('PUT', r'user/(?:premiereGachaPity|headlinerGachaPity)')
def set_gacha_pity(ctx):
    return {}


@route('GET', r'billing/purchase/status')
def purchase_status(ctx):
    return {'data': {}}


# ---- mail -----------------------------------------------------------------------------------------

@route('GET', r'mails')
def mails(ctx):
    return {'data': {}}


@route('GET', r'mail/count/unread')
def unread_mail(ctx):
    return {'data': {'count': 0}}


# ---- chat (PubNub based, disabled) ------------------------------------------------------------------

@route('GET', r'chat/info')
def chat_info(ctx):
    return {'data': {'enabled': False, 'publishKey': '', 'subscribeKey': '', 'channelCount': 0,
                     'idealChannelOccupancy': 0, 'maxChannelOccupancy': 0, 'chatChannelGroups': [], 'name': ''}}


@route('GET', r'chat/pubnub/accessToken')
def chat_token(ctx):
    return {'data': ''}


@route('GET', r'guildChatInfo')
def guild_chat_info(ctx):
    return {'data': {}}


# ---- deployments (Mission Control) ---------------------------------------------------------------

@route('GET', r'deployments')
def deployments(ctx):
    return {'data': []}


# ---- ads --------------------------------------------------------------------------------------------

@route('GET', r'ads/info')
def ads_info(ctx):
    return {'watchCount': 0, 'cycleWatchCount': 0, 'adsAvailableAt': 0}


# ---- social / guild (multiplayer features: intentionally empty) -------------------------------------

@route('GET', r'friends')
def friends(ctx):
    return {'data': []}


@route('GET', r'user/guild')
def user_guild(ctx):
    return {'data': {}}


@route('GET', r'guildOpsProgress')
def guild_ops_progress(ctx):
    return {'data': {}}


@route('GET', r'raidBoss/progress')
def raid_boss_progress(ctx):
    return {'data': {}}


@route('GET', r'guildFriends')
def guild_friends(ctx):
    return {'data': []}


@route('GET', r'guilds')
def guilds(ctx):
    return {'data': []}


@route('GET', r'replays')
def replays(ctx):
    return {'data': {}}


@route('GET', r'user/cosmetics')
def cosmetics(ctx):
    return {'data': {'portraitsOwned': [], 'newPortraits': []}}


@route('PUT', r'user/cosmetics/markPortraitsSeen')
def portraits_seen(ctx):
    return {}


@route('GET', r'loadouts/(?P<guid>[^/]+)')
def loadouts(ctx, guid):
    return {'data': []}
