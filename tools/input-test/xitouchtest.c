/* Xlib/libXi decoding probe; dlopen keeps all ABIs runnable against guest libraries. MIT licence. */
#include <X11/Xlib.h>
#include <X11/extensions/XInput2.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#define F(name) __typeof__(&name) name
static struct {
 F(XOpenDisplay);F(XQueryExtension);F(XCreateSimpleWindow);F(XSelectInput);F(XMapWindow);F(XStoreName);
 F(XPending);F(XNextEvent);F(XGetEventData);F(XFreeEventData);F(XFlush);F(XCloseDisplay);
 F(XCreateGC);F(XSetForeground);F(XFillRectangle);F(XSetInputFocus);
 F(XIQueryVersion);F(XIQueryDevice);F(XIFreeDeviceInfo);F(XISelectEvents);F(XIGrabTouchBegin);F(XIAllowTouchEvents);
} p;
#define LOAD(lib,name) do { *(void**)(&p.name)=dlsym(lib,#name);if(!p.name){printf("missing %s\n",#name);return 2;} }while(0)
int main(int argc,char **argv){
 setvbuf(stdout,NULL,_IOLBF,0);
 void *x=dlopen("libX11.so.6",RTLD_NOW|RTLD_GLOBAL),*xi=dlopen("libXi.so.6",RTLD_NOW);
 if(!x||!xi){puts(dlerror());return 1;}
 LOAD(x,XOpenDisplay);LOAD(x,XQueryExtension);LOAD(x,XCreateSimpleWindow);LOAD(x,XSelectInput);LOAD(x,XMapWindow);
 LOAD(x,XStoreName);LOAD(x,XPending);LOAD(x,XNextEvent);LOAD(x,XGetEventData);LOAD(x,XFreeEventData);LOAD(x,XFlush);
 LOAD(x,XCloseDisplay);LOAD(x,XCreateGC);LOAD(x,XSetForeground);LOAD(x,XFillRectangle);LOAD(x,XSetInputFocus);
 LOAD(xi,XIQueryVersion);LOAD(xi,XIQueryDevice);LOAD(xi,XIFreeDeviceInfo);LOAD(xi,XISelectEvents);
 LOAD(xi,XIGrabTouchBegin);LOAD(xi,XIAllowTouchEvents);
 Display *d=p.XOpenDisplay(NULL);if(!d){puts("no display");return 1;}
 int op,ev,err,major=2,minor=2;
 if(!p.XQueryExtension(d,"XInputExtension",&op,&ev,&err))return 1;
 p.XIQueryVersion(d,&major,&minor);printf("XI %d.%d opcode=%d ABI=%zu\n",major,minor,op,sizeof(void*)*8);
 int count;XIDeviceInfo *devices=p.XIQueryDevice(d,XIAllDevices,&count);
 for(int i=0;i<count;i++){
  XIDeviceInfo *v=devices+i;printf("DEVICE id=%d name=%s use=%d attachment=%d enabled=%d classes=%d\n",v->deviceid,v->name,v->use,v->attachment,v->enabled,v->num_classes);
  for(int j=0;j<v->num_classes;j++){
   XIAnyClassInfo *c=v->classes[j];
   if(c->type==XITouchClass){XITouchClassInfo *t=(void*)c;printf(" TOUCH source=%d mode=%d capacity=%d\n",t->sourceid,t->mode,t->num_touches);}
   if(c->type==XIValuatorClass){XIValuatorClassInfo *a=(void*)c;printf(" AXIS source=%d n=%d mode=%d range=%g..%g\n",a->sourceid,a->number,a->mode,a->min,a->max);}
  }
 }
 p.XIFreeDeviceInfo(devices);
 Window root=DefaultRootWindow(d),w=p.XCreateSimpleWindow(d,root,0,0,1280,720,0,0,0x183040);
 p.XStoreName(d,w,"XI2 native touch probe");
 p.XSelectInput(d,w,ExposureMask|ButtonPressMask|ButtonReleaseMask|PointerMotionMask|StructureNotifyMask);
 unsigned char normal[4]={0},raw[4]={0};
 XISetMask(normal,XI_TouchBegin);XISetMask(normal,XI_TouchUpdate);XISetMask(normal,XI_TouchEnd);XISetMask(normal,XI_TouchOwnership);
 XISetMask(normal,XI_Motion);XISetMask(normal,XI_ButtonPress);XISetMask(normal,XI_ButtonRelease);
 XISetMask(raw,XI_RawTouchBegin);XISetMask(raw,XI_RawTouchUpdate);XISetMask(raw,XI_RawTouchEnd);
 XISetMask(raw,XI_RawMotion);XISetMask(raw,XI_RawButtonPress);XISetMask(raw,XI_RawButtonRelease);
 int selector=argc>2?atoi(argv[2]):XIAllMasterDevices;
 XIEventMask mask={selector,sizeof normal,normal},rm={selector,sizeof raw,raw};
 p.XISelectEvents(d,w,&mask,1);p.XISelectEvents(d,root,&rm,1);
 const char *grab=argc>3?argv[3]:"";
 if(*grab){XIGrabModifiers mod={XIAnyModifier,0};int result=p.XIGrabTouchBegin(d,selector,w,False,&mask,1,&mod);printf("GRAB result=%d modifier_status=%d\n",result,mod.status);}
 p.XMapWindow(d,w);p.XSetInputFocus(d,w,RevertToParent,CurrentTime);p.XFlush(d);
 printf("READY selector=%d window=0x%lx\n",selector,w);
 GC gc=p.XCreateGC(d,w,0,NULL);
 unsigned down=0,move=0,up=0,core=0,mouse=0,rawcount=0;
 time_t until=time(NULL)+(argc>1?atoi(argv[1]):120);
 while(time(NULL)<until){
  while(p.XPending(d)){
   XEvent e;p.XNextEvent(d,&e);
   if(e.type==Expose){p.XSetForeground(d,gc,0x183040);p.XFillRectangle(d,w,gc,0,0,1280,720);p.XFlush(d);}
   if(e.type==MotionNotify||e.type==ButtonPress||e.type==ButtonRelease){core++;printf("CORE_MOUSE type=%d\n",e.type);}
   if(e.type!=GenericEvent||e.xcookie.extension!=op||!p.XGetEventData(d,&e.xcookie))continue;
   int t=e.xcookie.evtype;
   if(t>=XI_TouchBegin&&t<=XI_TouchEnd){
    XIDeviceEvent *a=e.xcookie.data;
    if(t==XI_TouchBegin)down++;else if(t==XI_TouchUpdate)move++;else up++;
    printf("TOUCH type=%d device=%d source=%d id=%u time=%lu root=%.4f,%.4f local=%.4f,%.4f flags=%x buttons=%d valuators=%d\n",t,a->deviceid,a->sourceid,a->detail,a->time,a->root_x,a->root_y,a->event_x,a->event_y,a->flags,a->buttons.mask_len,a->valuators.mask_len);
    if(*grab&&strcmp(grab,"pending")==0&&t==XI_TouchEnd)p.XIAllowTouchEvents(d,a->deviceid,a->detail,w,XIRejectTouch);
   }else if(t==XI_TouchOwnership){
    XITouchOwnershipEvent *a=e.xcookie.data;printf("OWNERSHIP device=%d source=%d id=%u\n",a->deviceid,a->sourceid,a->touchid);
    if(*grab&&strcmp(grab,"pending"))p.XIAllowTouchEvents(d,a->deviceid,a->touchid,w,!strcmp(grab,"reject")?XIRejectTouch:XIAcceptTouch);
   }else if(t>=XI_RawTouchBegin&&t<=XI_RawTouchEnd){
    XIRawEvent *a=e.xcookie.data;rawcount++;printf("RAW type=%d device=%d source=%d id=%u x=%.4f y=%.4f\n",t,a->deviceid,a->sourceid,a->detail,a->raw_values[0],a->raw_values[1]);
   }else if(t==XI_Motion||t==XI_ButtonPress||t==XI_ButtonRelease||t==XI_RawMotion||t==XI_RawButtonPress||t==XI_RawButtonRelease){mouse++;printf("XI_MOUSE type=%d\n",t);}
   p.XFreeEventData(d,&e.xcookie);p.XFlush(d);
  }
  usleep(5000);
 }
 printf("TOTAL down=%u move=%u up=%u raw=%u core_mouse=%u xi_mouse=%u\n",down,move,up,rawcount,core,mouse);
 p.XCloseDisplay(d);return down==up?0:3;
}
