FROM amazon/aws-lambda-python:3.12

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN dnf install -y \
    alsa-lib \
    at-spi2-atk \
    at-spi2-core \
    atk \
    cups-libs \
    gtk3 \
    libdrm \
    libXcomposite \
    libXcursor \
    libXdamage \
    libXext \
    libXi \
    libXrandr \
    libXScrnSaver \
    libXtst \
    libxkbcommon \
    mesa-libgbm \
    nspr \
    nss \
    pango \
    xorg-x11-fonts-Type1 \
    xorg-x11-fonts-misc \
    && dnf clean all

COPY requirements.txt ${LAMBDA_TASK_ROOT}/requirements.txt
RUN pip install --no-cache-dir -r ${LAMBDA_TASK_ROOT}/requirements.txt \
    && python -m playwright install chromium

COPY app.py ${LAMBDA_TASK_ROOT}/app.py

CMD ["app.handler"]
