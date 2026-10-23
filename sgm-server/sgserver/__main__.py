"""python -m sgserver [--config config.json] [--content content/] [--port 443] [--cert cert.pem --key key.pem]"""
import argparse
import json
import logging
import os

from .content import Content
from .enemies import EnemyFactory
from .formulas import Formulas
from .player import PlayerService
from .server import App, serve
from .store import Store
from . import handlers  # noqa: F401  (registers routes)

DEFAULT_CONFIG = {
    # new players skip the tutorial and start with the tutorial's final team
    'skip_tutorials': True,
    'starting_currencies': {'SoftCurrency': 50000, 'HardCurrency': 500, 'DailyEventTicket': 4},
    'extra_starting_characters': [],
    'collection_capacity': 1000,
    # reward tuning (1.0 = values derived from the game data)
    'xp_rate': 1.0,
    'currency_rate': 1.0,
    'player_xp_per_enemy_fs': 0.25,
    'country_code': 'US',
    # Limited-Time-Act events: 'rotate' (lta_sets_per_day sets each UTC day) or 'all'
    'lta_events': 'rotate',
    'lta_sets_per_day': 3,
    # sign-in: password accounts are created on first login
    'auto_register_password_accounts': True,
    'allow_implicit_signup': True,
}


def build_app(content_dir, db_path, config):
    content = Content(content_dir)
    formulas = Formulas(content)
    app = App(content, Store(db_path), config)
    app.formulas = formulas
    app.players = PlayerService(content, formulas, config)
    app.enemies = EnemyFactory(content, formulas)
    return app


def main():
    p = argparse.ArgumentParser(prog='sgserver', description='Skullgirls Mobile private server')
    p.add_argument('--config', help='JSON file overriding defaults')
    p.add_argument('--content', default='content', help='directory produced by tools/extract_content.py')
    p.add_argument('--db', default='sgserver.db')
    p.add_argument('--host', default='0.0.0.0')
    p.add_argument('--port', type=int, default=443)
    p.add_argument('--cert', help='TLS certificate (PEM); omit for plain HTTP')
    p.add_argument('--key', help='TLS private key (PEM)')
    p.add_argument('-v', '--verbose', action='store_true', help='log request/response bodies')
    a = p.parse_args()
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format='%(asctime)s %(levelname)s %(message)s')
    config = dict(DEFAULT_CONFIG)
    if a.config and os.path.exists(a.config):
        with open(a.config) as f:
            config.update(json.load(f))
    app = build_app(a.content, a.db, config)
    serve(app, a.host, a.port, a.cert, a.key or a.cert)


if __name__ == '__main__':
    main()
