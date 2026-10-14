const mouse = { x: 0, y: 0 }; // 遊標位置
const pos = { x: 0, y: 0 }; // 跟蹤到達位置

document.querySelector('body').addEventListener('mousemove', ({ x, y }) => {
  mouse.x = x;
  mouse.y = y;
});

requestAnimationFrame(function movement() {
  const easting = 10;
  pos.x += (mouse.x - pos.x) / easting;
  pos.y += (mouse.y - pos.y) / easting;
  const xValue = calcValue(pos.x, window.innerWidth);
  const yValue = calcValue(pos.y, window.innerHeight);

  const mediaView = document.querySelector('#media-layer-view');
  const mediaFront = document.querySelector('#media-layer-front');
  if (mediaView && mediaFront) {
    mediaView.style.transform = `translate3d(${xValue}px, ${yValue}px, 0) rotateX(${-yValue}deg) rotateY(${xValue}deg)`;
    mediaFront.style.transform = `translate3d(${
      xValue * 7.7
    }px, ${yValue * 3}px, 50px) rotateX(${-yValue}deg) rotateY(${xValue}deg)`;
  }

  const authCard = document.querySelector('#login-form, #signupForm, .reset-card');
  if (authCard) {
    authCard.style.transform = `perspective(5000px) translate3d(${xValue * 3}px, ${
      yValue * 2
    }px, 50px) rotateX(${-yValue}deg) rotateY(${xValue}deg)`;
  }

  requestAnimationFrame(movement);
});

function calcValue(a, b) {
  const range = 8;
  return (a / b) * range - range / 2;
}
