"""Patches: one module per concern. Each exposes NAME, DESCRIPTION, DEFAULT_ON and apply(ctx).

A patch mutates the output :class:`~sgmo_patcher.zipio.ZipPlan` through the :class:`Ctx` helpers
and records what it did on the :class:`~sgmo_patcher.report.PatchRecord` it is given. It never
writes files itself. The orchestrator (:mod:`sgmo_patcher.patcher`) decides which patches run and
in what order, and does the signing and verification.
"""

from __future__ import annotations

from . import merge_splits, redirect_api, pairip_neutralize, pairip_bootstrap, debloat, embed_server

#: Patches in apply order. merge-splits first (it adds the native libs and fixes the manifest so the
#: thing installs), then the metadata redirect, then the dex neutralisation.
ALL = [merge_splits, redirect_api, pairip_neutralize, pairip_bootstrap, debloat, embed_server]

BY_NAME = {m.NAME: m for m in ALL}
