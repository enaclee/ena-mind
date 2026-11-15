#!/usr/bin/env python3
"""Конфигурация ENA-MIND v6.0: настраиваемые комиссии, режимы, приоры, источники.

v6.0 (ENA-MIND): единственная биржа — Bybit V5 (Binance полностью удалён из рабочих
источников). Биржевые комиссии — Bybit VIP0/derivatives (maker 0.02 / taker 0.055).
По умолчанию берём более консервативный taker 0.10 — эталон издержек C=0.176%
(maker-in/taker-out на фьючерсах) сохранён из v4/v5, чтобы не менять эталонные тесты.

Иерархия: DEFAULTS -> data/config.json -> env-переменные (MM5_*).
Префикс MM5_ сохранён ради совместимости с существующими конфигурациями и данными.
Каталог данных: env MM5_DATA_DIR (используется на Android/Chaquopy) -> ../data (desktop).
"""
import json,os
def data_dir():
    """Каталог рабочих данных. Переопределяется env MM5_DATA_DIR (Android),
    по умолчанию — ../data относительно скриптов (как в v4/v5)."""
    d=os.environ.get("MM5_DATA_DIR")
    if d: return d
    return os.path.join(os.path.dirname(os.path.abspath(__file__)),"..","data")
BASE=data_dir()
CFG_PATH=os.path.join(BASE,"config.json")
# Комиссии по типам рынка, % от оборота. Bybit derivatives VIP0: maker 0.02/taker 0.055.
# По умолчанию taker поднят до 0.10 (консервативнее) — эталон v4/v5: C=0.176%.
FEES_DEFAULT={"spot":{"maker":0.100,"taker":0.180},
              "fut":{"maker":0.036,"taker":0.100},
              "tradfi":{"maker":0.000,"taker":0.0275}}
# Приоры аналитиков по режимам рынка. Это и есть «психология фазы»:
# в тренде доверяем ta выше, в range — flow и deriv (контр-тренд), в volatile — снижаем всё.
PRIORS_BY_REGIME={
    "trend_up":   {"ta":0.60,"flow":0.55,"deriv":0.53,"sentiment":0.52},
    "trend_down": {"ta":0.60,"flow":0.55,"deriv":0.53,"sentiment":0.52},
    "range":      {"ta":0.52,"flow":0.55,"deriv":0.58,"sentiment":0.55},  # контр-тренд давит
    "volatile":   {"ta":0.52,"flow":0.52,"deriv":0.52,"sentiment":0.50}, # всё шум — приоры к нейтрали
    "unknown":    {"ta":0.55,"flow":0.55,"deriv":0.55,"sentiment":0.53},
}
# Пороги совета и ворот.
DEFAULTS={
    "deposit":100.0,"market":"fut","fee_in":"maker","fee_out":"taker",
    "rr_min":2.0,"default_lev":3.0,"max_lev":10.0,
    "max_risk_pct":2.0,"max_margin_pct":50.0,"min_liq_buffer_x":3.0,"mmr_pct":0.5,
    "max_open":1,"max_trades_per_day":3,"max_daily_loss_pct":3.0,"blocked":[],
    "qty_step":0.001,"min_qty":0.001,"min_notional":100.0,"min_p_grade_c":0.6,
    "min_conf":0.5,"conflict_min_conf":0.6,"max_revisits":1,"p_floor":0.5,"p_cap":0.8,
    "fees":FEES_DEFAULT,
    "funding_extreme_pct":0.05,   # |funding| >= этого -> deriv экстремум (без истории)
    "funding_neutral_pct":0.01,   # |funding| <= этого -> deriv нейтрален
    "lsr_extreme":1.4,             # long/short account ratio: >1.4 толпа лонг, <0.7 толпа шорт
    "lsr_low":0.7,
    "fng_extreme_low":25,          # Fear&Greed: <=25 Fear (контр-тренд вверх), >=75 Greed (контр-тренд вниз)
    "fng_extreme_high":75,
    "min_klines":201,              # для ta нужно >=201 свечей
    "ta_trend_mode":True,          # v6.1: ta голосует и в УСТАНОВИВШЕМСЯ тренде, не только на свежем пробое MA28
    "funding_abs_floor":0.05,      # v6.1: |funding| ниже пола -> перцентиль НЕ считается экстремумом (база ~0.01%)
    "lsr_pctl_high":90.0,          # v6.1: LSR-перцентиль за 7д (API 168 набл.): >=90 -> толпа лонгует аномально
    "lsr_pctl_low":10.0,           # v6.1: <=10 -> толпа шортует аномально (фикс: база Bybit ~2.5, порог 1.4 срабатывал всегда)
    "lsr_api_len":168,             # глубина истории account-ratio (7 дней x 24ч) для перцентиля
    "lsr_hist_min_n":24,           # минимум записей для перцентиля, иначе фиксированные пороги 1.4/0.7
    "news_enabled":True,           # v6.1: Python-модуль новостей (RSS) для sentiment-фона
    "news_max_age_h":48,           # новости старше этого отбрасываются
    "p_hat_min_n":20,              # ниже — LOW CONFIDENCE
    "use_feedback_prior":True,     # использовать p_hat из журнала как приоры аналитиков
    # v6: новая функциональность ENA-MIND
    "funding_hist_len":90,         # история funding для перцентиля: ~30д x 3 выплаты
    "oi_hist_len":25,              # история OI (1h) для oi_pct_change: ~24ч
    "forecast_horizon_h":24,       # горизонт проверки прогноза по умолчанию
    "forecast_min_modules":2,      # минимум живых аналитиков для направленного прогноза
    "news_max_weight":0.10,        # максимальный вклад новостного фона в голос sentiment
    "sources":{"bybit":"https://api.bybit.com",
               "fng":"https://api.alternative.me/fng"},
}
def load(path=None):
    """Загрузка конфигурации: DEFAULTS <- config.json <- env MM5_* (скаляры)."""
    cfg=json.loads(json.dumps(DEFAULTS))  # глубокая копия
    p=path or CFG_PATH
    if os.path.exists(p):
        try:
            with open(p,encoding="utf-8") as f:
                user=json.load(f)
            for k,v in user.items():
                if k=="fees" and isinstance(v,dict):
                    cfg["fees"].update(v)
                elif k=="sources" and isinstance(v,dict):
                    cfg["sources"].update(v)
                else:
                    cfg[k]=v
        except Exception:
            pass  # битый config — молча откат к дефолту, лучше рабочее чем никакого
    # env-переопределения для скаляров (например MM5_max_risk_pct=1.5)
    for k in list(cfg):
        if isinstance(cfg[k],(int,float,str)) and not isinstance(cfg[k],bool):
            env=os.environ.get("MM5_"+k)
            if env is not None:
                try:
                    cfg[k]=type(cfg[k])(env) if not isinstance(cfg[k],float) else float(env)
                except ValueError: pass
    return cfg
def prior_for(module,regime,cfg=None):
    """Приор p модуля в данном режиме. Это «психология фазы»: в range deriv важнее ta."""
    c=cfg or load()
    r=regime if regime in PRIORS_BY_REGIME else "unknown"
    return float(PRIORS_BY_REGIME[r].get(module,0.55))
def fees(market,cfg=None):
    c=cfg or load()
    return c.get("fees",FEES_DEFAULT).get(market,FEES_DEFAULT["fut"])
def selftest():
    c=load()
    assert c["max_risk_pct"]==2.0
    assert fees("fut",c)["maker"]==0.036
    assert prior_for("ta","trend_up",c)==0.60
    assert prior_for("deriv","range",c)==0.58
    assert prior_for("ta","nonsense",c)==0.55  # неизвестный режим -> unknown
    # v6: только Bybit, Binance-ключа нет
    assert "bybit" in c["sources"] and "binance" not in c["sources"]
    assert c["forecast_horizon_h"]==24 and c["forecast_min_modules"]==2
    # v6.1: ключи починки офлайн-агентов
    assert c["ta_trend_mode"] is True and c["funding_abs_floor"]==0.05
    assert c["lsr_pctl_high"]==90.0 and c["lsr_pctl_low"]==10.0
    assert c["lsr_hist_min_n"]==24 and c["news_enabled"] is True
    # env override
    os.environ["MM5_max_risk_pct"]="1.5"
    assert load()["max_risk_pct"]==1.5
    del os.environ["MM5_max_risk_pct"]
    # data_dir override
    old=os.environ.get("MM5_DATA_DIR")
    os.environ["MM5_DATA_DIR"]="/tmp/em_test_data"
    assert data_dir()=="/tmp/em_test_data"
    if old is None: del os.environ["MM5_DATA_DIR"]
    else: os.environ["MM5_DATA_DIR"]=old
    print("mm_config: OK")
if __name__=="__main__": selftest()
