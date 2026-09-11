# AntiColisión 360 Core 2.3 — política de riesgo

- Corredor visual de 2 m total, adaptativo al pavimento mediante RoadSurfaceEstimator.
- Cualquier objeto móvil o estático que toque una de las líneas del corredor produce aviso preventivo.
- La trayectoria lateral se proyecta usando el borde del objeto y su velocidad lateral; si se prevé ingreso al corredor, la alerta escala según TTC lateral y distancia.
- Vehículo de perfil dentro del corredor: seguimiento anticipado hasta aproximadamente 70 m, con atención especial desde 40 m o más; si confirma cruce, escala a rojo.
- Vehículos que circulan paralelos en nuestra misma dirección, sin tocar ni proyectar ingreso al corredor: seguimiento visual con destello amarillo silencioso.
- Umbrales frontales de proximidad se mantienen: 6–8 m amarillo, 5–6 m rojo intermitente, <5 m rojo crítico permanente con alarma fuerte.
