/* Native SDL finger probe. No SDL headers needed; builds against the runtime's SDL2 ABI. MIT licence. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
typedef struct { int x,y,w,h; } Rect;
typedef struct { uint32_t type,time; int64_t touch,finger; float x,y,dx,dy,pressure; uint32_t window; } Finger;
static void *sdl;
#define FN(ret,name,args) static ret (*name) args
FN(int,SDL_Init,(uint32_t)); FN(void,SDL_Quit,(void)); FN(const char*,SDL_GetError,(void));
FN(void*,SDL_CreateWindow,(const char*,int,int,int,int,uint32_t)); FN(void*,SDL_CreateRenderer,(void*,int,uint32_t));
FN(int,SDL_PollEvent,(void*)); FN(void,SDL_Delay,(uint32_t)); FN(int,SDL_SetHint,(const char*,const char*));
FN(int,SDL_GetNumTouchDevices,(void)); FN(int64_t,SDL_GetTouchDevice,(int));
FN(int,SDL_SetRenderDrawColor,(void*,uint8_t,uint8_t,uint8_t,uint8_t)); FN(int,SDL_RenderClear,(void*));
FN(int,SDL_RenderFillRect,(void*,const Rect*)); FN(void,SDL_RenderPresent,(void*));
#define LOAD(n) do { *(void**)(&n)=dlsym(sdl,#n); if(!n){fprintf(stderr,"missing %s\n",#n);return 2;} } while(0)
int main(int argc,char **argv) {
    setvbuf(stdout,NULL,_IOLBF,0);
    setenv("SDL_TOUCH_MOUSE_EVENTS","0",1); setenv("SDL_MOUSE_TOUCH_EVENTS","0",1);
    sdl=dlopen("libSDL2-2.0.so.0",RTLD_NOW); if(!sdl)sdl=dlopen("libSDL2.so",RTLD_NOW);
    if(!sdl){fprintf(stderr,"%s\n",dlerror());return 1;}
    LOAD(SDL_Init);LOAD(SDL_Quit);LOAD(SDL_GetError);LOAD(SDL_CreateWindow);LOAD(SDL_CreateRenderer);
    LOAD(SDL_PollEvent);LOAD(SDL_Delay);LOAD(SDL_SetHint);LOAD(SDL_GetNumTouchDevices);LOAD(SDL_GetTouchDevice);
    LOAD(SDL_SetRenderDrawColor);LOAD(SDL_RenderClear);LOAD(SDL_RenderFillRect);LOAD(SDL_RenderPresent);
    SDL_SetHint("SDL_TOUCH_MOUSE_EVENTS","0");SDL_SetHint("SDL_MOUSE_TOUCH_EVENTS","0");
    if(SDL_Init(0x20)){puts(SDL_GetError());return 1;}
    void *w=SDL_CreateWindow("Native touchscreen test",0,0,1280,720,4);
    void *r=w?SDL_CreateRenderer(w,-1,1):NULL;
    if(!r){puts(SDL_GetError());return 1;}
    printf("SDL touch devices=%d\n",SDL_GetNumTouchDevices());
    for(int i=0;i<SDL_GetNumTouchDevices();i++)printf("device=%lld\n",(long long)SDL_GetTouchDevice(i));
    struct { int held; int64_t id; float x,y; } points[32]={0};
    unsigned down=0,move=0,up=0,mouse=0,max=0;
    time_t until=time(NULL)+(argc>1?atoi(argv[1]):300);
    while(time(NULL)<until) {
        union { uint64_t alignment; unsigned char bytes[128]; Finger finger; } event;
        while(SDL_PollEvent(&event)) {
            uint32_t type;memcpy(&type,event.bytes,4);
            if(type==0x100)goto done;
            if(type>=0x300&&type<=0x303)continue;
            if(type>=0x400&&type<=0x403){mouse++;printf("MOUSE type=%x count=%u\n",type,mouse);}
            if(type<0x700||type>0x702)continue;
            Finger *f=&event.finger;
            if(type==0x700)down++;else if(type==0x701)up++;else move++;
            int slot=-1;
            for(int i=0;i<32;i++)if(points[i].held&&points[i].id==f->finger){slot=i;break;}
            if(slot<0&&type==0x700)for(int i=0;i<32;i++)if(!points[i].held){slot=i;points[i].id=f->finger;break;}
            if(slot>=0){points[slot].held=type!=0x701;points[slot].x=f->x;points[slot].y=f->y;}
            unsigned held=0;for(int i=0;i<32;i++)held+=points[i].held;
            if(held>max)max=held;
            printf("FINGER type=%x device=%lld id=%lld x=%.5f y=%.5f active=%u down=%u move=%u up=%u mouse=%u\n",
                type,(long long)f->touch,(long long)f->finger,f->x,f->y,held,down,move,up,mouse);
        }
        SDL_SetRenderDrawColor(r,20,27,38,255);SDL_RenderClear(r);
        for(int i=0;i<32;i++)if(points[i].held){
            SDL_SetRenderDrawColor(r,70+i*29%180,220-i*23%150,100+i*11%150,255);
            Rect p={(int)(points[i].x*1280)-35,(int)(points[i].y*720)-35,70,70};SDL_RenderFillRect(r,&p);
        }
        SDL_RenderPresent(r);SDL_Delay(8);
    }
done:
    printf("TOTAL down=%u move=%u up=%u mouse=%u max_contacts=%u\n",down,move,up,mouse,max);
    SDL_Quit();return down==up?0:3;
}
