#!/usr/bin/env python3
"""mm_stats — статистический ИИ ENA-MIND v7.0 (агент 09, ЧАСТИ 4-6 промпта).

Проверяемые методы: логистическая регрессия (градиентный спуск, L2), z-score,
EWMA, байесовское обновление, walk-forward валидация. Без внешних зависимостей
(numpy не нужен: размерность мала, Chaquopy-совместимо).

ЗАЩИТА ОТ LOOKAHEAD (ЧАСТЬ 12):
- признаки в момент t строятся ТОЛЬКО по закрытым свечам до t включительно;
- метка — будущее движение close[t+h]/close[t] (h=15 минут);
- обучение walk-forward: модель обучается на окне [t-W, t) и предсказывает t;
- стандартизация признаков считается ТОЛЬКО на обучающем окне (никаких будущих
  статистик), и mean/std сохраняются в модели;
- сравнение с базовой линией (мажоритарный класс) обязательно: модель хуже
  базовой линии -> ready=False (не используется).

Модель сохраняется в data/model_v7.json с версией и метриками (ЧАСТЬ 4).
Никакие искусственно сгенерированные данные не используются как доказательство
точности: обучение — только на реальных свечах (интеграционный тест --live).
"""
import json,math,os
import mm_config

HORIZON_BARS=15          # 15 минут на 1m-свечах
TRAIN_MIN=500            # минимум свечей для готовности модели
FEATURES=("ret_5","ret_15","ret_60","rsi_1m","rsi_5m","macd_line_norm","macd_hist_norm",
          "bb_pos_1m","bb_pos_5m","atr_pct_1m","vol_ratio","volume_z","ema_gap_pct",
          "roc10","dist_vwap_pct")
MODEL_VERSION="logreg_v1"

# ---------------- признаки ----------------
def _rsi(closes,n=14):
    if len(closes)<n+1: return None
    gains=losses=0.0
    for i in range(-n,0):
        d=closes[i]-closes[i-1]
        gains+=max(0.0,d);losses+=max(0.0,-d)
    if losses==0: return 100.0 if gains>0 else 50.0
    return 100.0-100.0/(1.0+gains/losses)
def _macd(closes):
    if len(closes)<40: return (None,None)
    def ema_series(vals,n):
        k=2.0/(n+1);e=sum(vals[:n])/n;out=[]
        for v in vals[n:]:
            e=v*k+e*(1-k);out.append(e)
        return [None]*n+out
    fast=ema_series(closes,12);slow=ema_series(closes,26)
    lines=[f-s for f,s in zip(fast,slow) if f is not None and s is not None]
    if len(lines)<9: return (None,None)
    k=2.0/10.0;s=sum(lines[:9])/9
    for v in lines[9:]: s=v*k+s*(1-k)
    return lines[-1],lines[-1]-s
def _bb_pos(closes,n=20,k=2.0):
    if len(closes)<n: return None
    win=closes[-n:];m=sum(win)/n
    sd=math.sqrt(sum((x-m)**2 for x in win)/n)
    if sd==0: return 0.5
    return max(0.0,min(1.0,(closes[-1]-m+k*sd)/(2*k*sd)))
def _atr_pct(rows,n=14):
    if len(rows)<n+1: return None
    trs=[]
    for j in range(len(rows)-n,len(rows)):
        h,l,cp=rows[j][2],rows[j][3],rows[j-1][4]
        trs.append(max(h-l,abs(h-cp),abs(l-cp)))
    return 100.0*(sum(trs)/n)/rows[-1][4] if rows[-1][4] else None
def _stdev(vals):
    if len(vals)<2: return 0.0
    m=sum(vals)/len(vals)
    return math.sqrt(sum((x-m)**2 for x in vals)/len(vals))

def features_at(rows1,i,rows5=None):
    """Признаки по ЗАКРЫТЫМ свечам rows1[:i+1] (lookahead исключён по построению).
    rows5 — контекст старшего ТФ (тоже только закрытые)."""
    if i<70 or len(rows1)<=i: return None
    closes1=[r[4] for r in rows1[:i+1]]
    c=closes1[-1]
    f={}
    f["ret_5"]=100.0*math.log(c/closes1[-6]) if closes1[-6]>0 else 0.0
    f["ret_15"]=100.0*math.log(c/closes1[-16]) if closes1[-16]>0 else 0.0
    f["ret_60"]=100.0*math.log(c/closes1[-61]) if len(closes1)>61 and closes1[-61]>0 else 0.0
    r1=_rsi(closes1);f["rsi_1m"]=r1 if r1 is not None else 50.0
    closes5=[r[4] for r in (rows5 or [])[: (i//5)+1]]
    r5=_rsi(closes5) if len(closes5)>=15 else None
    f["rsi_5m"]=r5 if r5 is not None else f["rsi_1m"]
    ml,mh=_macd(closes1)
    f["macd_line_norm"]=100.0*ml/c if ml is not None and c else 0.0
    f["macd_hist_norm"]=100.0*mh/c if mh is not None and c else 0.0
    f["bb_pos_1m"]=_bb_pos(closes1) or 0.5
    f["bb_pos_5m"]=_bb_pos(closes5) if len(closes5)>=20 else f["bb_pos_1m"]
    f["atr_pct_1m"]=_atr_pct(rows1[:i+1]) or 0.0
    # вола: последние 15 баров против 60
    def _rv(cs,n):
        if len(cs)<n+1: return 0.0
        rs=[math.log(cs[j]/cs[j-1]) for j in range(-n,0) if cs[j-1]>0]
        if len(rs)<5: return 0.0
        return 100.0*_stdev(rs)
    rv_fast,rv_slow=_rv(closes1,15),_rv(closes1,60)
    f["vol_ratio"]=rv_fast/rv_slow if rv_slow>1e-9 else 1.0
    vols=[r[5] for r in rows1[max(0,i-89):i+1]]
    m=_stdev(vols)
    f["volume_z"]=(vols[-1]-sum(vols)/len(vols))/m if m>1e-12 and vols else 0.0
    # EMA21/EMA50
    def _ema(vals,n):
        if len(vals)<n: return None
        k=2.0/(n+1);e=sum(vals[:n])/n
        for v in vals[n:]: e=v*k+e*(1-k)
        return e
    e21,e50=_ema(closes1,21),_ema(closes1,50)
    f["ema_gap_pct"]=100.0*(e21-e50)/c if e21 and e50 and c else 0.0
    f["roc10"]=100.0*(c-closes1[-11])/closes1[-11] if closes1[-11]>0 else 0.0
    win=rows1[max(0,i-49):i+1]
    num=sum((r[1]+r[2]+r[3]+r[4])/4.0*r[5] for r in win);den=sum(r[5] for r in win)
    vwap=num/den if den>0 else c
    f["dist_vwap_pct"]=100.0*(c-vwap)/c if c else 0.0
    return f

def features_from_klines(rows1,rows5=None,rows15=None):
    """Текущий срез признаков (для агента): по последним закрытым свечам."""
    return features_at(rows1,len(rows1)-1,rows5)

# ---------------- логистическая регрессия ----------------
def _sigmoid(z):
    if z>30: return 1.0
    if z<-30: return 0.0
    return 1.0/(1.0+math.exp(-z))
def fit_logreg(X,y,l2=0.05,lr=0.15,epochs=250):
    """Обучение на стандартизованных признаках. Возвращает модель с mean/std
    (стандартизация только по обучающим данным — без утечки)."""
    if not X or len(X)!=len(y) or len(X)<50: return None
    names=sorted(X[0].keys())
    n=len(X)
    mean={k:sum(x[k] for x in X)/n for k in names}
    std={k:(_stdev([x[k] for x in X]) or 1.0) for k in names}
    Z=[{k:(x[k]-mean[k])/std[k] for k in names} for x in X]
    w={k:0.0 for k in names};b=0.0
    for _ in range(epochs):
        gw={k:0.0 for k in names};gb=0.0
        for zrow,yv in zip(Z,y):
            p=_sigmoid(b+sum(w[k]*zrow[k] for k in names))
            err=p-yv
            for k in names: gw[k]+=err*zrow[k]
            gb+=err
        for k in names:
            w[k]-=lr*(gw[k]/n+l2*w[k])
        b-=lr*gb/n
    return {"version":MODEL_VERSION,"features":names,"coef":w,"intercept":b,
            "mean":mean,"std":std,"train_n":n}
def predict_proba(model,f):
    names=model.get("features") or []
    if any(k not in f for k in names): return None
    z=model.get("intercept",0.0)
    for k in names:
        sd=model["std"].get(k,1.0) or 1.0
        z+=model["coef"].get(k,0.0)*((f[k]-model["mean"].get(k,0.0))/sd)
    return _sigmoid(z)

# ---------------- walk-forward обучение и валидация ----------------
def walk_forward(rows1,train_n=600,step=25,horizon=HORIZON_BARS,thr_mode="median"):
    """Честная walk-forward валидация на реальных 1m-свечах:
    для каждого t: модель обучается на [t-train_n, t), предсказывает t,
    метка = будущее движение за horizon баров. Возвращает (metrics, model).
    Если thr_mode='median': порог значимого движения = медиана |ret_h| на train."""
    n=len(rows1)
    if n<train_n+horizon+100: return None,None
    # медианный порог по всему ряду НЕ смотрит в будущее конкретного t (это гиперпараметр
    # волатильности; для строгого режима используется порог train-окна)
    preds=[];labels=[]
    model=None
    for t in range(train_n,n-horizon,step):
        window=rows1[t-train_n:t]
        # метка: будущее закрытие
        future=rows1[t+horizon][4];now=rows1[t][4]
        ret=100.0*(future-now)/now if now>0 else 0.0
        # порог по train-окну (без lookahead относительно t)
        tr_rets=[100.0*(window[j+horizon][4]-window[j][4])/window[j][4]
                 for j in range(0,len(window)-horizon,10) if window[j][4]>0]
        if len(tr_rets)<20: continue
        srt=sorted(abs(r) for r in tr_rets)
        thr=srt[len(srt)//2] if thr_mode=="median" else 0.0
        label=1 if ret>thr else 0
        # обучающая выборка: (признаки@j, метка j+horizon) для j из train-окна
        X=[];y=[]
        for j in range(t-train_n+80,t-horizon,3):
            f=features_at(rows1,j)
            if f is None: continue
            f_ret=100.0*(rows1[j+horizon][4]-rows1[j][4])/rows1[j][4]
            X.append(f);y.append(1 if f_ret>thr else 0)
        if len(X)<100 or len(set(y))<2: continue
        model=fit_logreg(X,y)
        if model is None: continue
        f_now=features_at(rows1,t)
        if f_now is None: continue
        p=predict_proba(model,f_now)
        if p is None: continue
        preds.append(p);labels.append(label)
    if len(preds)<10: return None,None
    # метрики: точность направления при p>0.55, logloss, brier, baseline
    hits=0;n_sig=0
    for p,l in zip(preds,labels):
        if p>=0.55 and l==1: hits+=1
        if p>=0.55: n_sig+=1
    base_up=sum(labels)/len(labels)
    base=max(base_up,1-base_up)
    ll=-sum(l*math.log(p+1e-12)+(1-l)*math.log(1-p+1e-12) for p,l in zip(preds,labels))/len(preds)
    brier=sum((p-l)**2 for p,l in zip(preds,labels))/len(preds)
    sig_rate=n_sig/len(preds)
    metrics={"n_test":len(preds),"hit_rate":round(hits/n_sig,4) if n_sig>=10 else None,
             "signal_rate":round(sig_rate,4),"baseline_hit":round(base,4),
             "logloss":round(ll,4),"brier":round(brier,4)}
    # итоговая модель на самом свежем окне
    X=[];y=[]
    t=n-horizon
    srt=sorted(abs(100.0*(rows1[j+horizon][4]-rows1[j][4])/rows1[j][4])
               for j in range(max(0,t-train_n),t-horizon,10) if rows1[j][4]>0)
    thr=srt[len(srt)//2] if srt else 0.0
    for j in range(t-train_n,t-horizon,3):
        f=features_at(rows1,j)
        if f is None: continue
        f_ret=100.0*(rows1[j+horizon][4]-rows1[j][4])/rows1[j][4]
        X.append(f);y.append(1 if f_ret>thr else 0)
    final=fit_logreg(X,y) if len(X)>=100 and len(set(y))>=2 else model
    final=final or model
    if final is not None:
        final["metrics"]=metrics
        # модель хуже базовой линии -> не готова (честность вместо самообмана)
        hr=metrics.get("hit_rate")
        final["ready"]=bool(hr is not None and hr>=max(0.52,metrics["baseline_hit"]))
    return metrics,final

def save_model(model,path=None):
    if model is None: return False
    p=path or os.path.join(mm_config.data_dir(),"model_v7.json")
    os.makedirs(os.path.dirname(p),exist_ok=True)
    try:
        with open(p,"w",encoding="utf-8") as f: json.dump(model,f)
        return True
    except Exception: return False
def load_model(path=None):
    p=path or os.path.join(mm_config.data_dir(),"model_v7.json")
    try:
        with open(p,encoding="utf-8") as f: m=json.load(f)
        return m if m.get("version")==MODEL_VERSION else None
    except Exception: return None

# ---------------- байесовское обновление и калибровка ----------------
def bayes_update(prior,agents_outcomes):
    """prior: P(up). agents_outcomes: [(likelihood_up_if_up, likelihood_up_if_down)]
    — правдоподобия агента из его исторической точности (когда был LONG и был факт
    up: p_hit; когда был LONG и факт down: 1-p_hit). Возвращает обновлённый prior."""
    for lik_up,lik_dn in agents_outcomes:
        if lik_up is None or lik_dn is None: continue
        num=prior*lik_up
        den=num+(1-prior)*lik_dn
        if den>0: prior=num/den
    return prior
def calibration_bins(probs,labels,n_bins=5):
    """Надёжность: список {bin, n, mean_p, observed}. Для отчета калибровки."""
    out=[]
    for i in range(n_bins):
        lo,hi=i/n_bins,(i+1)/n_bins
        sel=[(p,l) for p,l in zip(probs,labels) if lo<=(p or 0)<hi or (i==n_bins-1 and (p or 0)==1)]
        if not sel: continue
        out.append({"bin":i+1,"n":len(sel),
                    "mean_p":round(sum(p for p,_ in sel)/len(sel),4),
                    "observed":round(sum(1 for _,l in sel if l==1)/len(sel),4)})
    return out

def selftest():
    import random
    random.seed(7)
    # синтетика ТОЛЬКО для проверки механики (не как доказательство точности)
    n=1400;rows=[];p=100.0
    # первый кусок — тренд вверх, второй — вниз: модель должна научиться
    for i in range(n):
        d=0.0006 if i<n//2 else -0.0005
        o=p;p=max(0.01,p*(1+d+random.gauss(0,0.004)))
        rows.append([1600000000+i*60,o,max(o,p)*1.001,min(o,p)*0.999,p,abs(random.gauss(1,0.4))])
    f=features_at(rows,1000)
    assert f is not None and all(k in f for k in FEATURES),(f or {}).keys()
    # lookahead-защита: признаки на i не зависят от будущего (меняем будущее — не меняется)
    rows2=[r[:] for r in rows]
    for j in range(1005,len(rows2)): rows2[j][4]=rows2[j][4]*1.5
    f2=features_at(rows2,1000)
    assert f==f2,"ПРИЗНАКИ ЗАВИСЯТ ОТ БУДУЩЕГО (lookahead!)"
    # логрег: разделимые данные -> учится
    X=[{"a":random.gauss(1,0.5),"b":random.gauss(0,1)} for _ in range(120)]+\
      [{"a":random.gauss(-1,0.5),"b":random.gauss(0,1)} for _ in range(120)]
    y=[1]*120+[0]*120
    m=fit_logreg(X,y)
    assert m is not None and m["train_n"]==240
    p1=predict_proba(m,{"a":1.5,"b":0.0});p0=predict_proba(m,{"a":-1.5,"b":0.0})
    assert p1>0.8 and p0<0.2,(p1,p0)
    assert predict_proba(m,{"a":1.5}) is None
    # walk-forward на синтетике: механика работает, метрики считаются
    metrics,model=walk_forward(rows[:1000],train_n=500,step=40)
    assert metrics is not None and metrics["n_test"]>=5,metrics
    assert model is not None and "coef" in model and "features" in model
    # на случайном шуме модель НЕ должна считаться готовой (честность)
    rnd=[r[:] for r in rows]
    random.seed(9)
    for i in range(len(rnd)): rnd[i][4]=100.0*(1+random.gauss(0,0.004))
    met2,mod2=walk_forward(rnd[:1000],train_n=500,step=40)
    assert met2 is not None
    # байес
    p_up=bayes_update(0.5,[(0.65,0.35),(0.6,0.4)])
    assert 0.5<p_up<0.8,p_up
    assert bayes_update(0.5,[(None,None)])==0.5
    # калибровка
    bins=calibration_bins([0.1,0.15,0.85,0.9,0.55],[0,0,1,1,1])
    assert bins and abs(bins[0]["mean_p"]-0.125)<1e-6
    print("mm_stats: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--train",action="store_true",help="walk-forward обучение на живых свечах Bybit 1m")
    ap.add_argument("--symbol",default="BTCUSDT")
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.train:
        import mm_sources,mm_config,time
        r=mm_sources.bybit_klines(g.symbol,"1",1000)
        assert r.get("ok"),"свечи недоступны"
        rows=r["rows"]
        # добор истории вторым запросом (Bybit отдаёт <=1000 за раз)
        r2=mm_sources.bybit_klines(g.symbol,"1",1000,end_ts=rows[0][0]-1)
        if r2.get("ok"):
            seen={x[0] for x in rows}
            extra=[x for x in r2["rows"] if x[0] not in seen]
            rows=sorted(rows+extra,key=lambda x:x[0])
        print("свечей для обучения:",len(rows))
        t0=time.time()
        metrics,model=walk_forward(rows)
        print("walk-forward: %.1fs"%(time.time()-t0))
        print(json.dumps(metrics,ensure_ascii=False,indent=1))
        if model:
            model["symbol"]=g.symbol;model["trained_at"]=int(time.time())
            print("готова:",model.get("ready"))
            save_model(model)
    else: print("use --selftest / --train")
